package io.github.fomin.oasgen.java.dto.jackson.annotated

import io.github.fomin.oasgen.JsonSchema

class CompositeConverterMatcher(private val converterMatchers: List<ConverterMatcher>) : ConverterMatcher {
    override fun match(converterRegistry: ConverterRegistry, jsonSchema: JsonSchema): Converter? {
        converterMatchers.forEach { converterMatcher ->
            val converterWriter = converterMatcher.match(converterRegistry, jsonSchema)
            if (converterWriter != null) return converterWriter
        }
        return null
    }
}
