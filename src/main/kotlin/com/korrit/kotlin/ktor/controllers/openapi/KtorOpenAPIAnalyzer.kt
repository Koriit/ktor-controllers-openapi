package com.korrit.kotlin.ktor.controllers.openapi

import com.korrit.kotlin.ktor.controllers.HttpHeader
import com.korrit.kotlin.ktor.controllers.Input
import com.korrit.kotlin.ktor.controllers.InputKey
import com.korrit.kotlin.ktor.controllers.ResponsesKey
import com.korrit.kotlin.ktor.controllers.delegates.HeaderParamDelegate
import com.korrit.kotlin.ktor.controllers.delegates.PathParamDelegate
import com.korrit.kotlin.ktor.controllers.delegates.QueryParamDelegate
import com.korrit.kotlin.ktor.controllers.openapi.exceptions.AnalysisException
import com.korrit.kotlin.ktor.controllers.patch.AbstractPatchDelegate
import com.korrit.kotlin.ktor.controllers.patch.PatchOf
import com.korrit.kotlin.ktor.controllers.patch.RequiredNestedPatchDelegate
import com.korrit.kotlin.ktor.controllers.patch.RequiredPatchDelegate
import com.korrit.kotlin.openapi.model.Header
import com.korrit.kotlin.openapi.model.MediaType
import com.korrit.kotlin.openapi.model.OpenAPI
import com.korrit.kotlin.openapi.model.Operation
import com.korrit.kotlin.openapi.model.Parameter
import com.korrit.kotlin.openapi.model.Path
import com.korrit.kotlin.openapi.model.Property
import com.korrit.kotlin.openapi.model.RequestBody
import com.korrit.kotlin.openapi.model.Response
import com.korrit.kotlin.openapi.model.Schema
import io.ktor.application.Application
import io.ktor.application.feature
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Patch
import io.ktor.http.content.OutgoingContent.NoContent
import io.ktor.routing.HttpMethodRouteSelector
import io.ktor.routing.Route
import io.ktor.routing.Routing
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.OffsetTime
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

/**
 * Analyzes Ktor application and produces OpenAPI object.
 *
 * There is *no* direct code analysis as it depends on the presence of certain elements provided by [Ktor Controllers](https://github.com/Koriit/ktor-controllers).
 *
 * This analyzer holds internal state and thus it is not thread safe(NTS).
 *
 * @property ktor application to analyze
 * @property basePaths filter found routes by provided base paths
 * @property defaultResponseHeaders headers always present in the response
 * @property defaultContentType implicit content type
 * @property defaultErrorType implicit type of error responses
 */
@ExperimentalStdlibApi
@Suppress("TooManyFunctions") // all those functions are open to allow overriding
open class KtorOpenAPIAnalyzer(
    val ktor: Application,
    val basePaths: List<String> = listOf(""),
    val defaultResponseHeaders: List<HttpHeader> = emptyList(),
    val defaultContentType: ContentType = ContentType.Application.Json,
    val defaultErrorType: KClass<*> = Unit::class
) {

    protected open var routes = mutableMapOf<String, Path>()

    /**
     * Analyzes your Ktor application and returns OpenAPI object describing it.
     *
     * @throws AnalysisException in case of any failure
     */
    @Suppress(
        "TooGenericExceptionCaught", // Intended
        "RethrowCaughtException" // FIXME, possible false-positive of detekt
    )
    open fun analyze(): OpenAPI {
        try {
            routes = mutableMapOf()
            analyzeRoute(ktor.feature(Routing))

            return OpenAPI(paths = routes.values.toList(), components = null)
        } catch (e: AnalysisException) {
            throw e
        } catch (e: Exception) {
            throw AnalysisException(e.message ?: "", e)
        }
    }

    protected open fun analyzeRoute(route: Route) {
        if (route.children.isEmpty()) {
            val path = route.parent.toString()

            val matches = basePaths.any { base ->
                path == base || path.length > base.length && path.substring(0, base.length + 1) == "$base/"
            }
            if (!matches) {
                return
            }

            val method = analyzeMethod(route)
            val (deprecated, parameters, requestBody) = analyzeInput(route)
            val responses = analyzeResponses(route, path)

            val operation = Operation(
                method = method,
                responses = responses,
                requestBody = requestBody,
                parameters = if (parameters.isNullOrEmpty()) null else parameters,
                deprecated = deprecated
            )

            val operations = routes[path]?.operations ?: emptyList()

            routes[path] = Path(path, operations + operation)
        } else {
            route.children.forEach {
                analyzeRoute(it)
            }
        }
    }

    protected open fun analyzeMethod(route: Route): String {
        return (route.selector as HttpMethodRouteSelector).method.value.lowercase()
    }

    protected open fun analyzeInput(route: Route): Triple<Boolean, List<Parameter>?, RequestBody?> {
        val inputProvider = route.attributes.getOrNull(InputKey) ?: return Triple(false, null, null)
        val input = inputProvider()

        val httpMethod = HttpMethod.parse(analyzeMethod(route).uppercase())

        // deprecated
        val deprecated = input._deprecated

        // requestBody
        val inputType = findSuperType(input::class, Input::class)!!
        val bodyType = inputType.arguments[0].type!!
        val requestBody = if (bodyType.classifier !in listOf(Nothing::class, Unit::class)) {
            val contentType = input._contentType ?: defaultContentType
            val schema = toSchema(contentType, bodyType, httpMethod)
            val content = listOf(MediaType(contentType = contentType.toString(), schema = schema))

            RequestBody(content, input._bodyRequired)
        } else {
            null
        }

        // parameters
        val parameters = input::class.memberProperties
            .filter {
                it.visibility == KVisibility.PUBLIC
            }
            .mapNotNull { prop ->
                @Suppress("UNCHECKED_CAST")
                prop as KProperty1<Input<*>, *>
                prop.javaField!!.isAccessible = true

                val isDeprecated = prop.hasAnnotation<Deprecated>() || prop.hasAnnotation<java.lang.Deprecated>()
                val delegate = prop.getDelegate(input)
                when (delegate) {
                    null -> null
                    is PathParamDelegate<*> -> Parameter(
                        name = delegate.name,
                        inside = "path",
                        required = delegate.required,
                        deprecated = isDeprecated,
                        description = null,
                        schema = toSchema(prop.returnType, httpMethod, default = if (!delegate.required) delegate.default else null)
                    )
                    is QueryParamDelegate<*> -> Parameter(
                        name = delegate.name,
                        inside = "query",
                        required = delegate.required,
                        deprecated = isDeprecated,
                        description = null,
                        schema = toSchema(prop.returnType, httpMethod, default = if (!delegate.required) delegate.default else null)
                    )
                    is HeaderParamDelegate<*> -> Parameter(
                        name = delegate.name,
                        inside = "header",
                        required = delegate.required,
                        deprecated = isDeprecated,
                        description = null,
                        schema = toSchema(prop.returnType, httpMethod, default = if (!delegate.required) delegate.default else null)
                    )
                    else -> throw AnalysisException("Unknown delegate in class ${input::class.qualifiedName}")
                }
            }

        return Triple(deprecated, parameters, requestBody)
    }

    protected open fun analyzeResponses(route: Route, path: String): List<Response> {
        val responseTypes = route.attributes.getOrNull(ResponsesKey) ?: throw AnalysisException("There are no responses declared for path: $path")

        val httpMethod = HttpMethod.parse(analyzeMethod(route).uppercase())

        return responseTypes.map { response ->
            var content: List<MediaType>? = null
            val responseBodyType = response.type ?: defaultErrorType
            if (responseBodyType != Unit::class && !responseBodyType.isSubclassOf(NoContent::class)) {
                val contentType = response.contentType ?: defaultContentType
                val schema = toSchema(contentType, responseBodyType.createType(), httpMethod) // FIXME: pass type
                content = listOf(MediaType(contentType = contentType.toString(), schema = schema))
            }
            val responseHeaders = defaultResponseHeaders + (response.headers ?: emptyList())
            val headers = responseHeaders.map {
                Header(it.name, it.required, it.deprecated, toSchema(it.type.createType(), httpMethod))
            }

            Response(response.status.value.toString(), content, response.status.description, if (headers.isEmpty()) null else headers)
        }
    }

    protected open fun toSchema(contentType: ContentType, type: KType, httpMethod: HttpMethod): Schema {
        return when (contentType) {
            ContentType.Text.Plain -> Schema(null, "string", false, false, null, null, null, null, null, null, null, null)
            ContentType.Application.OctetStream -> Schema(null, "string", false, false, "binary", null, null, null, null, null, null, null)
            ContentType.Application.Json -> toSchema(type, httpMethod)
            // Might be more in the future
            else -> throw AnalysisException("Cannot transform content type $contentType to OpenAPI Schema Object: no transformation")
        }
    }

    protected open fun toSchema(type: KType, httpMethod: HttpMethod, deprecated: Boolean = false, default: Any? = null): Schema {
        assert(type.classifier is KClass<*>)
        val classifier = type.classifier as KClass<*>
        val nullable = type.isMarkedNullable

        when (classifier) {
            // Primitives
            Int::class -> return Schema(null, "integer", deprecated, nullable, "int32", default, null, null, null, null, null, null)
            Long::class -> return Schema(null, "integer", deprecated, nullable, "int64", default, null, null, null, null, null, null)
            Float::class -> return Schema(null, "number", deprecated, nullable, "float", default, null, null, null, null, null, null)
            Double::class -> return Schema(null, "number", deprecated, nullable, "double", default, null, null, null, null, null, null)
            Boolean::class -> return Schema(null, "boolean", deprecated, nullable, null, default, null, null, null, null, null, null)

            Char::class -> return Schema(null, "string", deprecated, nullable, null, default, null, null, null, null, null, null)
            Byte::class -> return Schema(null, "string", deprecated, nullable, "binary", default, null, null, null, null, null, null)

            // Special arrays of primitives
            IntArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Int::class.createType(), httpMethod), null)
            LongArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Long::class.createType(), httpMethod), null)
            FloatArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Float::class.createType(), httpMethod), null)
            DoubleArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Double::class.createType(), httpMethod), null)
            BooleanArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Boolean::class.createType(), httpMethod), null)

            CharArray::class -> return Schema(null, "string", deprecated, nullable, null, default, null, null, null, null, null, null)
            ByteArray::class -> return Schema(null, "string", deprecated, nullable, "binary", default, null, null, null, null, null, null)

            // Special types
            String::class -> return Schema(null, "string", deprecated, nullable, null, default, null, null, null, null, null, null)
            OffsetDateTime::class -> return Schema(null, "string", deprecated, nullable, "date-time", default, null, null, null, null, null, null)
            LocalDate::class -> return Schema(null, "string", deprecated, nullable, "date", default, null, null, null, null, null, null)
            OffsetTime::class -> return Schema(null, "string", deprecated, nullable, "time", default, null, null, null, null, null, null)

            // We don't know how to handle this
            Any::class -> throw AnalysisException("Cannot transform Any to OpenAPI Schema Object: unknown transformation")
        }

        if (classifier.java.isPrimitive) {
            throw AnalysisException("Cannot transform primitive ${classifier.simpleName} to OpenAPI Schema Object: unknown transformation")
        }

        // Generic arrays
        if (classifier.java.isArray) {
            return arrayToSchema(type, classifier, httpMethod, deprecated, nullable)
        }

        // Lists
        if (classifier.isSubclassOf(List::class)) {
            return listToSchema(type, httpMethod, deprecated, nullable)
        }

        // Sets
        if (classifier.isSubclassOf(Set::class)) {
            return setToSchema(type, httpMethod, deprecated, nullable)
        }

        // Maps
        if (classifier.isSubclassOf(Map::class)) {
            return mapToSchema(type, classifier, httpMethod, deprecated, nullable)
        }

        // Enums
        if (classifier.java.isEnum) {
            return enumToSchema(classifier, deprecated, nullable, default)
        }

        // Objects
        return objectToSchema(classifier, deprecated, nullable, httpMethod)
    }

    // TODO: document it in detail
    protected open fun objectToSchema(classifier: KClass<*>, deprecated: Boolean, nullable: Boolean, httpMethod: HttpMethod): Schema {
        val properties = classifier.memberProperties
            .asSequence()
            .filter { it.visibility == KVisibility.PUBLIC }
            .map {
                val isDeprecated = it.hasAnnotation<Deprecated>() || it.hasAnnotation<java.lang.Deprecated>()
                val schema = toSchema(it.returnType, httpMethod, deprecated = isDeprecated)
                Property(it.name, schema)
            }
            .sortedBy { it.name }
            .toList()

        val constructorParams = classifier.primaryConstructor?.parameters
            ?: throw AnalysisException("Cannot transform type ${classifier.simpleName} to OpenAPI Schema Object: missing primary constructor")

        val requiredProperties = constructorParams
            .asSequence()
            .filter { param ->
                properties.find { it.name == param.name }
                    ?: throw AnalysisException("Cannot transform type ${classifier.simpleName} to OpenAPI Schema Object: primary constructor param is not a public property: ${param.name}")
                !param.isOptional
            }
            .map { it.name!! }
            .toMutableList()

        if (PatchOf::class.java.isAssignableFrom(classifier.java)) {
            requiredProperties += classifier.memberProperties
                .asSequence()
                .filter { it.javaField != null }
                .filter { httpMethod != Patch || it.javaField!!.type in listOf(RequiredNestedPatchDelegate::class.java, RequiredPatchDelegate::class.java) }
                .filter { param ->
                    properties.find { it.name == param.name }
                        ?: throw AnalysisException("Cannot transform type ${classifier.simpleName} to OpenAPI Schema Object: patch delegate is not a public property: ${param.name}")

                    AbstractPatchDelegate::class.java.isAssignableFrom(param.javaField!!.type)
                }
                .map { it.name }
                .toList()
        }

        return Schema(classifier.simpleName, "object", deprecated, nullable, null, null, null, if (requiredProperties.isNotEmpty()) requiredProperties.sorted() else null, properties, null, null, null)
    }

    protected open fun enumToSchema(classifier: KClass<*>, deprecated: Boolean, nullable: Boolean, default: Any? = null): Schema {
        val values = classifier.java.enumConstants.map { (it as Enum<*>).name }
        return Schema("${classifier.simpleName}Enum", "string", deprecated, nullable, null, default, null, null, null, null, null, values)
    }

    protected open fun mapToSchema(type: KType, classifier: KClass<*>, httpMethod: HttpMethod, deprecated: Boolean, nullable: Boolean): Schema {
        val itemType = findMapValueType(type) ?: throw AnalysisException("Cannot find Map supertype with type arguments of: $type")
        val itemCls = itemType.classifier as KClass<*>
        val itemSchema = if (itemCls != Any::class) toSchema(itemType, httpMethod) else null
        val title = if (itemCls != Any::class) "${itemCls.simpleName}Map" else classifier.simpleName

        return Schema(title, "object", deprecated, nullable, null, null, null, null, null, itemSchema, null, null)
    }

    protected open fun setToSchema(type: KType, httpMethod: HttpMethod, deprecated: Boolean, nullable: Boolean): Schema {
        val setType = findSuperType(type, AbstractSet::class)
            ?: findSuperType(type, java.util.AbstractSet::class)
            ?: findSuperType(type, Set::class)
            ?: findSuperType(type, java.util.Set::class)
            ?: throw AnalysisException("Cannot find Set supertype of: $type")
        val itemType = setType.arguments[0].type!!
        val itemCls = itemType.classifier as KClass<*>

        return Schema("${itemCls.simpleName}Set", "array", deprecated, nullable, null, null, true, null, null, null, toSchema(itemType, httpMethod), null)
    }

    protected open fun listToSchema(type: KType, httpMethod: HttpMethod, deprecated: Boolean, nullable: Boolean): Schema {
        val listType = findSuperType(type, AbstractList::class)
            ?: findSuperType(type, java.util.AbstractList::class)
            ?: findSuperType(type, List::class)
            ?: findSuperType(type, java.util.List::class)
            ?: throw AnalysisException("Cannot find List supertype of: $type")

        val itemType = listType.arguments[0].type!!
        val itemCls = itemType.classifier as KClass<*>

        return Schema("${itemCls.simpleName}List", "array", deprecated, nullable, null, null, null, null, null, null, toSchema(itemType, httpMethod), null)
    }

    protected open fun arrayToSchema(type: KType, classifier: KClass<*>, httpMethod: HttpMethod, deprecated: Boolean, nullable: Boolean): Schema {
        if (type.arguments.isNotEmpty()) {
            val itemType = type.arguments[0].type!!
            val itemCls = itemType.classifier as KClass<*>

            return Schema("${itemCls.simpleName}Array", "array", deprecated, nullable, null, null, null, null, null, null, toSchema(itemType, httpMethod), null)
        } else {
            throw AnalysisException("Cannot transform array ${classifier.simpleName} to OpenAPI Schema Object: unknown transformation")
        }
    }

    protected open fun findSuperType(haystack: KType, needle: KClass<*>): KType? {
        if (haystack.classifier == needle) {
            return haystack
        }
        return findSuperType(haystack.classifier as KClass<*>, needle)
    }

    protected open fun findSuperType(haystack: KClass<*>, needle: KClass<*>): KType? {
        haystack.supertypes.forEach {
            val classifier = it.classifier
            if (classifier == needle) return it

            if (classifier is KClass<*> && classifier.supertypes.isNotEmpty()) {
                findSuperType(classifier, needle)?.let {
                    return it
                }
            }
        }
        return null
    }

    protected open fun findMapValueType(map: KType): KType? {
        if (map.classifier !is KClass<*>) {
            return null
        }

        val cls = map.classifier as KClass<*>

        if (!cls.isSubclassOf(Map::class)) {
            return null
        }

        if (map.arguments.size == 2 && map.arguments[1].type!!.classifier is KClass<*>) {
            return map.arguments[1].type
        }

        for (superMap in cls.supertypes) {
            findMapValueType(superMap)?.let {
                return it
            }
        }

        return null
    }
}
