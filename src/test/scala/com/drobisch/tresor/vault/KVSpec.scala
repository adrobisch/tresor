package com.drobisch.tresor.vault

import cats.effect.{IO, Timer}
import com.drobisch.tresor.{StepClock, WireMockSupport, vault}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext
import scala.util.Random

class KVSpec extends AnyFlatSpec with Matchers with WireMockSupport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  "KV provider" should "read token from vault KV engine" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server =>
      IO.delay {
        val vaultSecretResponse = aResponse()
          .withStatus(200)
          .withBody(
            s"""{"request_id":"1","lease_id":"","renewable":false,"lease_duration":43200,"data":{"key":"value"},"wrap_info":null,"warnings":null,"auth":null}"""
          )

        val vaultSecretRequest = get(urlEqualTo("/v1/secret/treasure"))
          .withHeader("X-Vault-Token", equalTo("vault-token"))
          .willReturn(vaultSecretResponse)

        server.stubFor(vaultSecretRequest)
      }.flatMap { _ =>
        val vaultConfig = VaultConfig(
          apiUrl = s"http://localhost:${server.port()}/v1",
          token = "vault-token"
        )
        implicit val clock = StepClock(1)

        vault.KV[IO]("secret").secret(KeyValueContext(key = "treasure"), vaultConfig)
      }
    ).unsafeRunSync()

    result should be(
      Lease(
        leaseId = Some(""),
        data = Map("key" -> Some("value")),
        renewable = false,
        leaseDuration = Some(43200),
        1
      )
    )
  }

  "KV provider" should "read an existing token from vault KV engine (uses Docker Vault with pre-stored secret)" ignore {
    // Needs a v1 secret engine under "secret-v1" and a secret "foobar" with data: foo -> bar
    implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val timer: Timer[IO] = cats.effect.IO.timer(executionContext)

    val config = VaultConfig("http://0.0.0.0:8200/v1", "vault-plaintext-root-token")

    val kvSecret: IO[Lease] =
      KV[cats.effect.IO]("secret-v1").secret(KeyValueContext(key = "foobar"), config)

    val result = kvSecret.unsafeRunSync()
    result.data should be(
      Map("foo" -> Some("bar"))
    )
  }

  "KV provider" should "create, update and read a new token from vault KV engine (uses Docker Vault)" in {
    // Needs a v1 secret engine under "secret-v1"
    implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val timer: Timer[IO] = cats.effect.IO.timer(executionContext)

    val config = VaultConfig("http://0.0.0.0:8200/v1", "vault-plaintext-root-token")

    val secretName = Random.alphanumeric.take(10).mkString
    def createKvSecret: IO[Unit] =
      KV[cats.effect.IO]("secret-v1").createSecret(
        (KeyValueContext(key = secretName), config),
        Map("foo" -> Some("bar")))
    def updateKvSecret: IO[Unit] =
      KV[cats.effect.IO]("secret-v1").createSecret(
        (KeyValueContext(key = secretName), config),
        Map("foo" -> Some("baz")))
    def kvSecret: IO[Lease] =
      KV[cats.effect.IO]("secret-v1").secret(KeyValueContext(key = secretName), config)

    val result = (for {
      _ <- createKvSecret
      secret1 <- kvSecret
      _ <- updateKvSecret
      secret2 <- kvSecret
    } yield (secret1, secret2)).unsafeRunSync()

    result._1.data should be(
      Map("foo" -> Some("bar"))
    )
    result._2.data should be(
      Map("foo" -> Some("baz"))
    )
  }
}
