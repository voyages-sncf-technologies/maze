/*
 * Copyright (C) 2016 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.dt.maze.topology

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, Closeable, IOException}
import java.net.{Proxy, ProxySelector, SocketAddress, URI}
import java.util

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.{CreateContainerCmd, CreateNetworkResponse, InspectContainerResponse, ListContainersCmd}
import com.github.dockerjava.api.model.Network.Ipam
import com.github.dockerjava.api.model.Network.Ipam.Config
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder, DockerClientConfig}
import com.github.dockerjava.jaxrs.JerseyDockerCmdExecFactory
import com.typesafe.scalalogging.StrictLogging
import fr.vsct.dt.maze.core.{Commands, Execution}
import fr.vsct.dt.maze.helpers
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object Docker extends StrictLogging {

  val defaultRetries: Int = 4
  val defaultLowerBoundPort: Int = 50000
  val defaultUpperBoundPort: Int = 59999

  case class DockerExtraConfiguration(
                                       lowerBoundPort: Int = Docker.defaultLowerBoundPort,
                                       upperBoundPort: Int = Docker.defaultUpperBoundPort,
                                       dns: Seq[String] = Seq(),
                                       dnsSearch: Seq[String] = Seq(),
                                       networkIpRange: String = "10.20.0",
                                       networkName: String = "technical-tests",
                                       extraLabels: Map[String, String] = Map("test.runner" -> "maze")
                                     )

  var extraConfiguration: DockerExtraConfiguration = DockerExtraConfiguration()

  // Hack for docker-java to allow proxy using HTTP_PROXY / HTPS_PROXY env
  ProxySelector.setDefault{
    new ProxySelector {
      val defaultProxySelector: ProxySelector = ProxySelector.getDefault
      override def select(uri: URI): util.List[Proxy] = {
        val modified = if (uri.getScheme == "tcp") {
          new URI("http", uri.getUserInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)
        } else {
          uri
        }
        defaultProxySelector.select(modified)
      }

      override def connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException): Unit =
        defaultProxySelector.connectFailed(uri, socketAddress, e)
    }
  }

  def lowerBoundPort: Int = extraConfiguration.lowerBoundPort
  def upperBoundPort: Int = extraConfiguration.upperBoundPort

  def constructBinding(port: Int): PortBinding = PortBinding.parse(s"$lowerBoundPort-$upperBoundPort:$port")

  private def resolveDockerUri(): String =
    System.getProperty("DOCKER_HOST", Option(System.getenv("DOCKER_HOST")).getOrElse("unix:///var/run/docker.sock"))

  private def resolveTlsSupport(): Boolean =
    Option(System.getenv("DOCKER_TLS_VERIFY")).map(_ == "1").getOrElse(!resolveDockerUri().startsWith("unix"))

  var client: DockerClient = {
    val dockerApiVersion = Option(System.getenv("DOCKER_API_VERSION")).getOrElse("1.22")

    val configuration: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
      .withApiVersion(dockerApiVersion)
      .withDockerTlsVerify(resolveTlsSupport()).withDockerHost(resolveDockerUri()).build()

    val dockerCmdExecFactory = {
      val readTimeout = 60000
      val connectTimeout: Integer = 60000
      val totalConnections: Integer = 10
      val connectionsPerHost: Integer = 10

      new JerseyDockerCmdExecFactory()
        .withReadTimeout(readTimeout)
        .withConnectTimeout(connectTimeout)
        .withMaxTotalConnections(totalConnections)
        .withMaxPerRouteConnections(connectionsPerHost)
    }

    DockerClientBuilder.getInstance(configuration)
      .withDockerCmdExecFactory(dockerCmdExecFactory).build
  }

  private val TcpHost = """^tcp://([^:]+).*""".r

  val host: DockerHost = resolveDockerUri() match {
    case TcpHost(ip) => DockerHost(nameOrIp = ip)
    case _ => DockerHost(nameOrIp = "localhost")
  }

  case class DockerContainer(id: String)

  case class DockerProcessExecution(returnCode: Int, lines: List[String]) extends Exception {
    override def getMessage: String = lines.mkString("\n")
  }

  case class DockerHost(nameOrIp: String)

  def prepareCreateContainer(img: String): CreateContainerCmd = {
    val image = if (img.contains(":")) img else img + ":latest"
    val images = client.listImagesCmd().withImageNameFilter(image).exec()
    if (!images.asScala.exists(_.getRepoTags.contains(image))) {
      logger.info(s"Pulling image $image...")
      client.pullImageCmd(image).exec(new WaitForCallbackResponse[PullResponseItem]()).await()
    }

    var cmd = client.createContainerCmd(image)
      .withCapAdd(Capability.NET_ADMIN)
      .withPrivileged(true)
      .withNetworkMode(helpers.DockerNetwork.networkName)

    if(extraConfiguration.dns.nonEmpty) {
      cmd = cmd.withDns(extraConfiguration.dns:_*)
    }

    if(extraConfiguration.dnsSearch.nonEmpty) {
      cmd = cmd.withDnsSearch(extraConfiguration.dnsSearch:_*)
    }

    if(extraConfiguration.extraLabels.nonEmpty) {
      cmd = cmd.withLabels(extraConfiguration.extraLabels.asJava)
    }

    cmd
  }

  def createAndStartContainer(command: CreateContainerCmd): String = {
    val id = command.exec().getId
    retry(defaultRetries, s"startCreatedContainer($id)"){
      startCreatedContainer(id)
    }
    id
  }

  @annotation.tailrec
  def retry[T](n: Int, retryLibel: String)(fn: => T): T = {
    Try {
      fn
    } match {
      case Success(x) => x
      case Failure(e)  if n <= 1 =>
        logger.error(s"Error [0 retry left] during $retryLibel, propagate exception", e)
        throw e
      case Failure(e) if n > 1 =>
        logger.warn(s"Error [${n - 1} retry left] during $retryLibel", e)
        Commands.waitFor(250 milliseconds)
        retry(n - 1, retryLibel)(fn)
    }
  }

  def listContainers(): List[Container] = listContainers(client.listContainersCmd())

  def listContainers(cmd: ListContainersCmd): List[Container] = cmd.exec().asScala.toList

  def stopContainer(id: String): Unit = {
    client.stopContainerCmd(id).exec()
    client.waitContainerCmd(id).exec(new WaitForCallbackResponse[WaitResponse]())
  }

  def killContainer(id: String, signal: String = "KILL"): Unit = client.killContainerCmd(id).withSignal(signal).exec()

  def forceRemoveContainer(id: String): Unit = client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec()

  def executionOnContainer(id: String, commands: String*): Execution[Array[String]] = Execution {
    val cmdId = client.execCreateCmd(id)
      .withAttachStderr(true)
      .withAttachStdout(true)
      .withCmd(commands: _*)
      .exec().getId

    val execResult = client.execStartCmd(cmdId).exec(new LogAppender)
    execResult.await()

    val exitCode = client.inspectExecCmd(cmdId).exec().getExitCode
    val lines = execResult.result.replaceAll("\r\n", "\n").split("\n")

    if (Option(exitCode).exists(_ != 0)) {
      logger.debug(s"${commands.mkString(" ")} on $id had error: ${lines.mkString("\n")}")
      throw DockerProcessExecution(exitCode, lines.toList)
    }
    logger.debug(s"result of ${commands.mkString(" ")} on $id: [${lines.mkString("\n")}]")
    lines
  }.labeled(s"Execution of ${commands.mkString(" ")} on container $id")


  def getIp(containerId: String): String =
    containerInfo(containerId).getNetworkSettings.getNetworks.get(helpers.DockerNetwork.networkName).getIpAddress

  class LogAppender extends ResultCallback[Frame] {
    val builder = new StringBuilder
    var finished = false

    override def onError(throwable: Throwable): Unit = {
      builder.append("An error occurred").append(throwable.getMessage)
    }

    override def onComplete(): Unit = {
      finished = true
    }

    override def onStart(closeable: Closeable): Unit = {
      builder.clear()
    }

    override def onNext(frame: Frame): Unit = {
      builder.append(new String(frame.getPayload, "UTF-8"))
    }

    override def close(): Unit = {}

    def result: String = {
      builder.result()
    }

    @tailrec
    final def await(): LogAppender = {
      if (finished) {
        this
      } else {
        Commands.waitFor(100 milliseconds)
        await()
      }
    }
  }

  def containerInfo(id: String): InspectContainerResponse = {
    client.inspectContainerCmd(id).exec()
  }

  private class WaitForCallbackResponse[T] extends ResultCallback[T] {

    var value: List[T] = Nil
    var done = false

    override def onError(throwable: Throwable): Unit = {
      done = true
    }

    override def onComplete(): Unit = done = true

    override def onStart(closeable: Closeable): Unit = {}

    override def onNext(o: T): Unit = {
      value = o :: value
    }

    override def close(): Unit = {}

    @tailrec
    final def await(counter: Int = 0): Int = {
      if (done) {
        counter
      } else {
        Commands.waitFor(100 milliseconds)
        await(counter + 1)
      }
    }
  }

  def restartContainer(id: String): Unit = {
    stopContainer(id)
    startCreatedContainer(id)
  }

  def startCreatedContainer(id: String): Unit = {
    client.startContainerCmd(id).exec()
  }

  def createNetwork(name: String, subnet: String): CreateNetworkResponse = {
    val ipManagement: Ipam = new Ipam()
    val ipManagementConfig = new Config()
    ipManagementConfig.withSubnet(subnet)
    ipManagement.withConfig(ipManagementConfig)
    client.createNetworkCmd().withName(name).withIpam(ipManagement).exec()
  }

  def deleteNetwork(name: String): Unit = {
    client.removeNetworkCmd(name).exec()
  }

  def executionCreateFileOnContainer(id: String, path: String, content: String): Unit = {

    val index = path.lastIndexOf("/") + 1
    val directory = path.substring(0, index)
    val fileName = path.substring(index)

    val contentBytes = content.getBytes("utf-8")

    val outputStream = new ByteArrayOutputStream()

    val write = new TarArchiveOutputStream(outputStream)
    val entry = new TarArchiveEntry(fileName)
    entry.setSize(contentBytes.length)
    write.putArchiveEntry(entry)
    write.write(contentBytes)
    write.closeArchiveEntry()
    write.close()

    client.copyArchiveToContainerCmd(id)
      .withTarInputStream(new ByteArrayInputStream(outputStream.toByteArray))
      .withRemotePath(directory)
      .exec()


  }

  def logs(id: String): Execution[Array[String]] = Execution {

    val logs = client.logContainerCmd(id).withTailAll()
      .withStdOut(true)
      .withStdErr(true)
      .withFollowStream(false)
      .exec(new LogAppender).await()
      .result.split("\n")

    logger.trace(s"Got logs for container $id: ${logs.mkString("\n")}")
    logs
  }.labeled(s"logs of container $id")

}

