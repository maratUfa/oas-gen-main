package io.github.fomin.oasgen.java.reactor.netty

import io.github.fomin.oasgen.*
import io.github.fomin.oasgen.java.*
import io.github.fomin.oasgen.java.dto.jackson.wstatic.*
import java.util.*

class ReactorNettyServerWriter(
        private val basePackage: String,
        private val converterIds: List<String>
) : Writer<OpenApiSchema> {
    override fun write(items: Iterable<OpenApiSchema>): List<OutputFile> {
        val outputFiles = mutableListOf<OutputFile>()

        val converterMatcher = ConverterMatcherProvider.provide(basePackage, converterIds)
        val converterRegistry = ConverterRegistry(converterMatcher)
        val javaDtoWriter = JavaDtoWriter(converterRegistry)

        items.forEach { openApiSchema ->
            val clientClassName = toJavaClassName(basePackage, openApiSchema, "routes")
            val filePath = getFilePath(clientClassName)

            val importDeclarations = TreeSet<String>()

            importDeclarations.addAll(listOf(
                    "import com.fasterxml.jackson.core.JsonFactory;",
                    "import io.github.fomin.oasgen.ByteBufConverter;",
                    "import io.github.fomin.oasgen.UrlEncoderUtils;",
                    "import java.util.Map;",
                    "import java.util.function.Consumer;",
                    "import javax.annotation.Nonnull;",
                    "import javax.annotation.Nullable;",
                    "import reactor.core.publisher.Mono;",
                    "import reactor.netty.http.server.HttpServerRoutes;"
            ))

            val paths = openApiSchema.paths()
            val javaOperations = toJavaOperations(converterRegistry, paths)

            val operationMethods = javaOperations.map { javaOperation ->
                val parameterArgs = javaOperation.parameters.map { javaParameter ->
                    val nullAnnotation = when (javaParameter.schemaParameter.required) {
                        true -> "@Nonnull"
                        false -> "@Nullable"
                    }
                    "$nullAnnotation ${javaParameter.javaVariable.type} ${javaParameter.javaVariable.name}"
                }
                val requestBodyArg = javaOperation.requestVariable?.let { requestVariable ->
                    "@Nonnull Mono<${requestVariable.type}> requestBodyMono"
                }

                val args = (parameterArgs + requestBodyArg)
                        .filterNotNull()
                        .joinToString(", ")

                """|@Nonnull
                   |public abstract Mono<${javaOperation.responseVariable.type}> ${javaOperation.methodName}($args);
                   |
                """.trimMargin()
            }

            val routes = javaOperations.map { javaOperation ->
                val queryParamMapDeclaration = if (javaOperation.parameters.any { it.schemaParameter.parameterIn == ParameterIn.QUERY })
                    "Map<String, String> queryParams = UrlEncoderUtils.parseQueryParams(request.uri());"
                else
                    ""
                val parameterDeclarations = javaOperation.parameters.mapIndexedNotNull { index, javaParameter ->
                    when (val paramterIn = javaParameter.schemaParameter.parameterIn) {
                        ParameterIn.PATH -> """${javaParameter.javaVariable.type} param$index = request.param("${javaParameter.name}");"""
                        ParameterIn.QUERY -> """${javaParameter.javaVariable.type} param$index = queryParams.get("${javaParameter.name}");"""
                        else -> error("unsupported paramter type $paramterIn")
                    }
                }
                val requestMonoDeclaration = javaOperation.requestVariable?.let { requestVariable ->
                    "Mono<${requestVariable.type}> requestMono = byteBufConverter.parse(request.receive(), ${converterRegistry[requestVariable.schema].parserCreateExpression()});"
                } ?: ""
                val parameterArgs = javaOperation.parameters.mapIndexed { index, _ -> "param$index" }
                val requestArg = javaOperation.requestVariable?.let { "requestMono" }
                val args = (parameterArgs + requestArg).filterNotNull().joinToString(", ")
                val responseMonoDeclaration =
                        "Mono<${javaOperation.responseVariable.type}> responseMono = ${javaOperation.methodName}($args);"
                val returnStatement = when (val responseSchema = javaOperation.responseVariable.schema) {
                    null -> "return response.send();"
                    else -> """|return response
                               |        .header("Content-Type", "application/json")
                               |        .send(byteBufConverter.write(response, responseMono, ${converterRegistry[responseSchema].writerCreateExpression()}));
                            """.trimMargin()
                }

                val routeMethod = javaOperation.operation.operationType.name.toLowerCase()
                """|.$routeMethod(baseUrl + "${javaOperation.pathTemplate}", (request, response) -> {
                   |    $queryParamMapDeclaration
                   |    ${parameterDeclarations.indentWithMargin(1)}
                   |    ${requestMonoDeclaration.indentWithMargin(1)}
                   |    $responseMonoDeclaration
                   |    ${returnStatement.indentWithMargin(1)}
                   |})
                """.trimMargin()

            }

            val content = """
               |package ${getPackage(clientClassName)};
               |
               |${importDeclarations.indentWithMargin("")}
               |
               |public abstract class ${getSimpleName(clientClassName)} implements Consumer<HttpServerRoutes> {
               |    private final ByteBufConverter byteBufConverter;
               |    private final String baseUrl;
               |
               |    protected ${getSimpleName(clientClassName)}(JsonFactory jsonFactory, String baseUrl) {
               |        this.byteBufConverter = new ByteBufConverter(jsonFactory);
               |        this.baseUrl = baseUrl;
               |    }
               |
               |    ${operationMethods.indentWithMargin(1)}
               |
               |    @Override
               |    public final void accept(HttpServerRoutes httpServerRoutes) {
               |        httpServerRoutes
               |            ${routes.indentWithMargin(3)}
               |        ;
               |    }
               |}
               |
            """.trimMargin()

            val dtoSchemas = mutableListOf<JsonSchema>()
            dtoSchemas.addAll(javaOperations.mapNotNull { it.responseVariable.schema })
            dtoSchemas.addAll(javaOperations.mapNotNull { it.requestVariable?.schema })
            val dtoFiles = javaDtoWriter.write(dtoSchemas)
            outputFiles.addAll(dtoFiles)
            outputFiles.add(OutputFile(filePath, content))
        }

        return outputFiles
    }

}
