package com.vsct.dt.dsl

import com.vsct.dt.dsl.helpers.DockerNetwork
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}

abstract class TechnicalTest extends FlatSpec with Matchers with BeforeAndAfterEach with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    DockerNetwork.createDefaultNetwork()
  }

  override protected def afterAll(): Unit = {
    DockerNetwork.removeDefaultNetwork()
  }

}
