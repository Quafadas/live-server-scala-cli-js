import munit.CatsEffectSuite
import cats.effect.IO

class ConfigSpec extends CatsEffectSuite:

  test("Valid Config Should Load") {

    val config = """
http:
  servers:
    - listen: 8080
      serverNames:
        - foo.com
        - bar.com
      locations:
        - matcher: /
          proxyPass: http://127.0.0.1:8080
    - listen: 8081
      serverNames:
        - baz.com
      locations:
        - matcher: /
          proxyPass: "http://$big_server_com"

  upstreams:
    - name: big_server_com
      servers:
        - host: 127.0.0.3
          port: 8000
          weight: 5
        - host: 192.168.0.1
          port: 8000
"""
    ProxyConfig
      .loadYaml[IO](config)
      .attempt
      .map {
        e =>
          assert(e.isRight)
      }

  }
end ConfigSpec
