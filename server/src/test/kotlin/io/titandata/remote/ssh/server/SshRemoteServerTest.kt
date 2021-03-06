/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.ssh.server

import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.titandata.remote.RemoteOperation
import io.titandata.remote.RemoteOperationType
import io.titandata.remote.RemoteProgress
import io.titandata.shell.CommandException
import io.titandata.shell.CommandExecutor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.IllegalArgumentException

class SshRemoteServerTest : StringSpec() {
    @MockK
    lateinit var executor: CommandExecutor

    @InjectMockKs
    @OverrideMockKs
    var server = SshRemoteServer()

    override fun beforeTest(testCase: TestCase) {
        return MockKAnnotations.init(this)
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
        clearAllMocks()
    }

    val operation = RemoteOperation(
            updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
            remote = mapOf("username" to "user", "address" to "host", "path" to "/path"),
            parameters = mapOf("password" to "password"),
            operationId = "operation",
            commitId = "commit",
            commit = null,
            type = RemoteOperationType.PUSH
    )

    private fun mockFile(): File {
        mockkStatic("kotlin.io.FilesKt__FileReadWriteKt")
        mockkStatic(Files::class)
        val file: File = mockk()
        every { file.path } returns "/path"
        every { file.toPath() } returns mockk()
        every { file.writeText(any()) } just Runs
        every { Files.setPosixFilePermissions(any(), any()) } returns mockk()
        return file
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "get provider returns ssh" {
            server.getProvider() shouldBe "ssh"
        }

        "validate remote succeeds with only required fields" {
            val result = server.validateRemote(mapOf("username" to "user", "path" to "/path", "address" to "host"))
            result["username"] shouldBe "user"
            result["path"] shouldBe "/path"
            result["address"] shouldBe "host"
        }

        "validate remote fails if required field is missing" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("username" to "user", "address" to "host"))
            }
        }

        "validate remote succeeds with all optional fields" {
            val result = server.validateRemote(mapOf("username" to "user", "path" to "/path", "address" to "host",
                    "keyFile" to "/keyfile", "password" to "pass", "port" to 8022))
            result["username"] shouldBe "user"
            result["path"] shouldBe "/path"
            result["address"] shouldBe "host"
            result["password"] shouldBe "pass"
            result["keyFile"] shouldBe "/keyfile"
            result["port"] shouldBe 8022
        }

        "validate remote converts port" {
            val result = server.validateRemote(mapOf("username" to "user", "path" to "/path", "address" to "host",
                    "port" to 8022.0))
            result["port"] shouldBe 8022
        }

        "validate remote fails with bad port" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("username" to "user", "path" to "/path", "address" to "host",
                        "port" to "p"))
            }
        }

        "validate remote fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("username" to "user", "path" to "/path", "address" to "host",
                        "portz" to "p"))
            }
        }

        "validate params succeeds" {
            val params = server.validateParameters(mapOf("password" to "password", "key" to "key"))
            params["password"] shouldBe "password"
            params["key"] shouldBe "key"
        }

        "validate params fails for unknown property" {
            shouldThrow<IllegalArgumentException> {
                server.validateParameters(mapOf("password" to "password", "key" to "key", "keyz" to "key"))
            }
        }

        "ssh auth fails if neither password nor key is specified in parameters" {
            shouldThrow<IllegalArgumentException> {
                server.getSshAuth(emptyMap(), emptyMap())
            }
        }

        "ssh auth fails if both password and key is specified in parameters" {
            shouldThrow<IllegalArgumentException> {
                server.getSshAuth(emptyMap(), mapOf("password" to "password", "key" to "key"))
            }
        }

        "ssh auth returns password if specified in parameters" {
            val (password, key) = server.getSshAuth(emptyMap(), mapOf("password" to "password"))
            password shouldBe "password"
            key shouldBe null
        }

        "ssh auth returns password if specified in remote" {
            val (password, key) = server.getSshAuth(mapOf("password" to "password"), emptyMap())
            password shouldBe "password"
            key shouldBe null
        }

        "ssh auth returns key if specified in parameters" {
            val (password, key) = server.getSshAuth(emptyMap(), mapOf("key" to "key"))
            password shouldBe null
            key shouldBe "key"
        }

        "build SSH command uses sshpass for password authentication" {
            val file = mockFile()
            val command = server.buildSshCommand(emptyMap(), mapOf("password" to "password"), file, false)
            command shouldBe arrayOf("sshpass", "-f", "/path", "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null")
            verify {
                file.writeText("password")
            }
        }

        "build SSH command uses key file for key authentication" {
            val file = mockFile()
            val command = server.buildSshCommand(emptyMap(), mapOf("key" to "key"), file, false)
            command shouldBe arrayOf("ssh", "-i", "/path", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null")
            verify {
                file.writeText("key")
            }
        }

        "build SSH command with port and address succeeds" {
            val file = mockFile()
            val command = server.buildSshCommand(mapOf("port" to 1234, "username" to "user", "address" to "host"),
                    mapOf("key" to "key"), file, true, "ls", "/var/tmp")
            command shouldBe arrayOf("ssh", "-i", "/path", "-p", "1234", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "user@host", "ls", "/var/tmp")
        }

        "run ssh command invokes executor correctly" {
            every { executor.exec(*anyVararg()) } returns ""
            server.runSsh(mapOf("username" to "user", "address" to "host"), mapOf("key" to "key"), "ls", "-l")
            verify {
                executor.exec("ssh", "-i", any(), "-o", "StrictHostKeyChecking=no", "-o",
                "UserKnownHostsFile=/dev/null", "user@host", "ls", "-l")
            }
        }

        "get commit returns failure if file doesn't exist" {
            every { executor.exec(*anyVararg()) } throws CommandException("", 1, "No such file or directory")
            val result = server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit propagates unknown failures" {
            every { executor.exec(*anyVararg()) } throws CommandException("", 1, "")
            shouldThrow<CommandException> {
                server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            }
        }

        "get commit returns correct metadata" {
            every { executor.exec(*anyVararg()) } returns "{\"a\":\"b\"}"
            val result = server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "temporary password file is correctly removed" {
            val slot = slot<String>()
            every { executor.exec("sshpass", "-f", capture(slot), *anyVararg()) } returns "{\"id\":\"id\",\"properties\":{\"a\":\"b\"}}"
            server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            val file = File(slot.captured)
            file.exists() shouldBe false
        }

        "list commits returns an empty list" {
            every { executor.exec(*anyVararg()) } returns ""
            val result = server.listCommits(mapOf("username" to "user", "password" to "password", "address" to "host", "path" to "/var/tmp"), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "list commits returns correct metadata" {
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "ls", "-1", "/var/tmp") } returns "a\nb\n"
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "cat", "/var/tmp/a/metadata.json") } returns
                    "{\"timestamp\":\"2019-09-20T13:45:36Z\"}"
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "cat", "/var/tmp/b/metadata.json") } returns
                    "{\"timestamp\":\"2019-09-20T13:45:37Z\"}"
            val result = server.listCommits(mapOf("username" to "root", "password" to "password", "address" to "localhost", "path" to "/var/tmp"), emptyMap(), emptyList())
            result.size shouldBe 2
            result[0].first shouldBe "b"
            result[1].first shouldBe "a"
        }

        "list commits filters result" {
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "ls", "-1", "/var/tmp") } returns "a\nb\n"
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "cat", "/var/tmp/a/metadata.json") } returns
                    "{\"tags\":{\"c\":\"d\"}}"
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "cat", "/var/tmp/b/metadata.json") } returns
                    "{}"
            val result = server.listCommits(mapOf("username" to "root", "password" to "password", "address" to "localhost", "path" to "/var/tmp"), emptyMap(), listOf("c" to null))
            result.size shouldBe 1
            result[0].first shouldBe "a"
        }

        "list commits ignores missing file" {
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "ls", "-1", "/var/tmp") } returns "a\n"
            every { executor.exec("sshpass", "-f", any(), "ssh", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", "root@localhost", "cat", "/var/tmp/a/metadata.json") } throws CommandException("", 1,
                    "No such file or directory")

            val result = server.listCommits(mapOf("username" to "root", "password" to "password", "address" to "localhost", "path" to "/var/tmp"), emptyMap(), emptyList())
            result.size shouldBe 0
        }

        "write file succeeds" {
            val spy = spyk(server)
            every { spy.buildSshCommand(any(), any(), any(), any(), *anyVararg()) } returns emptyList()
            val process: Process = mockk()
            every { executor.start(*anyVararg()) } returns process
            every { executor.checkResult(any()) } just Runs
            val output = ByteArrayOutputStream()
            every { process.outputStream } returns output
            every { process.isAlive } returns false
            every { process.waitFor(any(), any()) } returns true

            spy.writeFileSsh(emptyMap(), emptyMap(), "/path", "content")

            output.toString() shouldBe "content"
        }

        "write file fails on timeout" {
            val spy = spyk(server)
            every { spy.buildSshCommand(any(), any(), any(), any(), *anyVararg()) } returns emptyList()
            val process: Process = mockk()
            every { executor.start(*anyVararg()) } returns process
            every { executor.checkResult(any()) } just Runs
            val output = ByteArrayOutputStream()
            every { process.outputStream } returns output
            every { process.isAlive } returns true
            every { process.waitFor(any(), any()) } returns true

            shouldThrow<IOException> {
                spy.writeFileSsh(emptyMap(), emptyMap(), "/path", "content")
            }
        }

        "get remote path returns correct information" {
            val result = server.getRemotePath(operation, null, "volume")
            result shouldBe "user@host:/path/commit/data/volume/"
        }

        "push metadata writes correct contents" {
            val spy = spyk(server)
            every { spy.writeFileSsh(any(), any(), any(), any()) } just Runs
            spy.pushMetadata(operation, mapOf("a" to "b"), true)
            verify {
                spy.writeFileSsh(any(), any(), "/path/commit/metadata.json", "{\"a\":\"b\"}")
            }
        }

        "get rsync creates directory on push" {
            val spy = spyk(server)
            every { spy.runSsh(any(), any(), *anyVararg()) } returns ""
            every { spy.getSshAuth(any(), any()) } returns Pair("password", null)
            spy.getRsync(operation, null, "/src", "user@host:/path/commit/volume", executor)
            verify {
                spy.runSsh(any(), any(), "mkdir", "-p", "/path/commit/volume")
            }
        }

        "sync data start does nothing" {
            server.syncDataStart(operation)
        }

        "sync data end does nothing" {
            server.syncDataEnd(operation, null, true)
        }
    }
}
