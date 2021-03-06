package io.github.fomin.oasgen

interface OpenApiWriterProvider {
    val id: String
    fun provide(namespace: String, converterIds: List<String>): Writer<OpenApiSchema>
}
