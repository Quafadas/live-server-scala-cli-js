package io.github.quafadas.sjsls

import com.comcast.ip4s.*
import com.monovore.decline.Command

import munit.FunSuite

class HostOptSuite extends FunSuite:

  private def parse(args: String*) =
    Command("LiveServer", "test")(LiveServer.parseOpts).parse(args)

  test("host defaults to localhost") {
    assertEquals(parse().map(_.host), Right(host"localhost"))
  }

  test("--host accepts an IPv4 wildcard") {
    assertEquals(parse("--host", "0.0.0.0").map(_.host), Right(ip"0.0.0.0"))
  }

  test("--host accepts an IPv6 address") {
    assertEquals(parse("--host", "::").map(_.host), Right(ip"::"))
  }

  test("--host rejects garbage") {
    assert(parse("--host", "not a host!").isLeft)
  }

  test("loopback detection") {
    assert(LiveServer.isLoopback(host"localhost"))
    assert(LiveServer.isLoopback(host"127.0.0.1"))
    assert(LiveServer.isLoopback(ip"::1"))
    assert(!LiveServer.isLoopback(host"0.0.0.0"))
    assert(!LiveServer.isLoopback(host"192.168.1.10"))
  }
end HostOptSuite
