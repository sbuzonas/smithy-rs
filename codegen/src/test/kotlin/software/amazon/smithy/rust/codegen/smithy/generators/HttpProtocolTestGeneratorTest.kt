package software.amazon.smithy.rust.codegen.smithy.generators

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.rust.codegen.lang.RustWriter
import software.amazon.smithy.rust.codegen.lang.rustBlock
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.util.CommandFailed
import software.amazon.smithy.rust.codegen.util.lookup
import software.amazon.smithy.rust.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.testutil.asSmithy
import software.amazon.smithy.rust.testutil.compileAndTest
import software.amazon.smithy.rust.testutil.testSymbolProvider

class HttpProtocolTestGeneratorTest {
    private val baseModel = """
        namespace com.example

        use aws.protocols#restJson1
        use smithy.test#httpRequestTests

        @restJson1
        service HelloService {
            operations: [SayHello],
            version: "1"
        }

        @http(method: "POST", uri: "/")
        @httpRequestTests([
            {
                id: "say_hello",
                protocol: restJson1,
                params: {
                    "greeting": "Hi",
                    "name": "Teddy",
                    "query": "Hello there"
                },
                method: "POST",
                uri: "/",
                queryParams: [
                    "Hi=Hello%20there"
                ],
                forbidQueryParams: [
                    "goodbye"
                ],
                requireQueryParams: ["required"],
                headers: {
                    "X-Greeting": "Hi",
                },
                body: "{\"name\": \"Teddy\"}",
                bodyMediaType: "application/json"
            }
        ])
        operation SayHello {
            input: SayHelloInput
        }

        structure SayHelloInput {
            @httpHeader("X-Greeting")
            greeting: String,

            @httpQuery("Hi")
            query: String,

            name: String
        }
    """.asSmithy()
    private val model = OperationNormalizer(testSymbolProvider(baseModel)).addOperationInputs(baseModel)
    private val symbolProvider = testSymbolProvider(model)
    private val runtimeConfig = TestRuntimeConfig

    /**
     * Creates an fake HTTP implementation for SayHello & generates the protocol test
     */
    private fun writeHttpImpl(writer: RustWriter, body: String) {
        writer.withModule("operation") {
            StructureGenerator(model, symbolProvider, this, model.lookup("com.example#SayHelloInput")).render()
            rustBlock("impl SayHelloInput") {
                rustBlock("pub fn build_http_request(&self) -> \$T", RuntimeType.HttpRequestBuilder) {
                    write("\$T::new()", RuntimeType.HttpRequestBuilder)
                    write(body)
                }
            }
            val protocolConfig = ProtocolConfig(
                model,
                symbolProvider,
                runtimeConfig,
                model.lookup("com.example#HelloService"),
                RestJson1Trait.ID
            )
            HttpProtocolTestGenerator(
                protocolConfig,
                model.lookup("com.example#SayHello"),
                this
            ).render()
        }
    }

    @Test
    fun `passing e2e protocol request test`() {
        val writer = RustWriter.forModule("lib")
        writeHttpImpl(
            writer,
            """
                    .uri("/?Hi=Hello%20there&required")
                    .header("X-Greeting", "Hi")
                    .method("POST")
                """
        )

        val testOutput = writer.compileAndTest()
        // Verify the test actually ran
        testOutput shouldContain "test_say_hello ... ok"
    }

    @Test
    fun `test invalid url parameter`() {
        val writer = RustWriter.forModule("lib")

        // Hard coded implementation for this 1 test
        writeHttpImpl(
            writer,
            """
                    .uri("/?Hi=INCORRECT&required")
                    .header("X-Greeting", "Hi")
                    .method("POST")
                """
        )

        val err = assertThrows<CommandFailed> {
            writer.compileAndTest(expectFailure = true)
        }
        // Verify the test actually ran
        err.message shouldContain "test_say_hello ... FAILED"
        err.message shouldContain "missing query param"
    }

    @Test
    fun `test forbidden url parameter`() {
        val writer = RustWriter.forModule("lib")

        // Hard coded implementation for this 1 test
        writeHttpImpl(
            writer,
            """
                    .uri("/?goodbye&Hi=Hello%20there&required")
                    .header("X-Greeting", "Hi")
                    .method("POST")
                """
        )

        val err = assertThrows<CommandFailed> {
            writer.compileAndTest(expectFailure = true)
        }
        // Verify the test actually ran
        err.message shouldContain "test_say_hello ... FAILED"
        err.message shouldContain "forbidden query param"
    }

    @Test
    fun `test required url parameter`() {
        val writer = RustWriter.forModule("lib")

        // Hard coded implementation for this 1 test
        writeHttpImpl(
            writer,
            """
                    .uri("/?Hi=Hello%20there")
                    .header("X-Greeting", "Hi")
                    .method("POST")
                """
        )

        val err = assertThrows<CommandFailed> {
            writer.compileAndTest(expectFailure = true)
        }
        // Verify the test actually ran
        err.message shouldContain "test_say_hello ... FAILED"
        err.message shouldContain "required query param missing"
    }

    @Test
    fun `invalid header`() {
        val writer = RustWriter.forModule("lib")
        writeHttpImpl(
            writer,
            """
                    .uri("/?Hi=Hello%20there&required")
                    // should be "Hi"
                    .header("X-Greeting", "Hey")
                    .method("POST")
                """
        )

        val err = assertThrows<CommandFailed> {
            writer.compileAndTest(expectFailure = true)
        }
        err.message shouldContain "test_say_hello ... FAILED"
        err.message shouldContain "invalid header value"
    }
}