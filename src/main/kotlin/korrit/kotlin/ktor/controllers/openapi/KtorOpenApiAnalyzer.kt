package korrit.kotlin.ktor.controllers.openapi

import io.ktor.application.Application
import io.ktor.application.feature
import io.ktor.http.ContentType
import io.ktor.routing.HttpMethodRouteSelector
import io.ktor.routing.Route
import io.ktor.routing.Routing
import io.ktor.util.KtorExperimentalAPI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.OffsetTime
import korrit.kotlin.ktor.controllers.HttpHeader
import korrit.kotlin.ktor.controllers.Input
import korrit.kotlin.ktor.controllers.InputKey
import korrit.kotlin.ktor.controllers.ResponsesKey
import korrit.kotlin.ktor.controllers.delegates.HeaderParamDelegate
import korrit.kotlin.ktor.controllers.delegates.PathParamDelegate
import korrit.kotlin.ktor.controllers.delegates.QueryParamDelegate
import korrit.kotlin.ktor.controllers.openapi.exceptions.AnalysisException
import korrit.kotlin.openapi.model.Header
import korrit.kotlin.openapi.model.MediaType
import korrit.kotlin.openapi.model.OpenAPI
import korrit.kotlin.openapi.model.Operation
import korrit.kotlin.openapi.model.Parameter
import korrit.kotlin.openapi.model.Path
import korrit.kotlin.openapi.model.Property
import korrit.kotlin.openapi.model.RequestBody
import korrit.kotlin.openapi.model.Response
import korrit.kotlin.openapi.model.Schema
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
 * @param ktor application to analyze
 * @param basePaths filter found routes by provided base paths
 * @param defaultResponseHeaders headers always present in the response
 * @param defaultContentType implicit content type
 * @param defaultErrorType implicit type of error responses
 */
@KtorExperimentalAPI
@ExperimentalStdlibApi
@Suppress("TooManyFunctions")
open class KtorOpenAPIAnalyzer(
    val ktor: Application,
    val basePaths: List<String> = listOf(""),
    val defaultResponseHeaders: List<HttpHeader> = emptyList(),
    val defaultContentType: ContentType = ContentType.Application.Json,
    val defaultErrorType: KClass<*> = Unit::class
) {

    protected open var routes = mutableListOf<Path>()

    /**
     * Analyzes your Ktor application and returns OpenAPI object describing it.
     *
     * @throws AnalysisException in case of any failure
     */
    @Suppress("TooGenericExceptionCaught") // Intended
    open fun analyze(): OpenAPI {
        try {
            routes = mutableListOf()
            analyzeRoute(ktor.feature(Routing))

            return OpenAPI(paths = routes, components = null)
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

            val pathObj = routes
                .find { it.path == path }
                ?: Path(path).also { routes.add(it) }

            val method = analyzeMethod(route)
            val (deprecated, parameters, requestBody) = analyzeInput(route)
            val responses = analyzeResponses(route, path)

            pathObj.operations.add(Operation(method, responses, requestBody, if (parameters.isNullOrEmpty()) null else parameters, deprecated))
        } else {
            route.children.forEach {
                analyzeRoute(it)
            }
        }
    }

    protected open fun analyzeMethod(route: Route): String {
        return (route.selector as HttpMethodRouteSelector).method.value.toLowerCase()
    }

    protected open fun analyzeInput(route: Route): Triple<Boolean, List<Parameter>?, RequestBody?> {
        val inputProvider = route.attributes.getOrNull(InputKey) ?: return Triple(false, null, null)
        val input = inputProvider()

        // deprecated
        val deprecated = input._deprecated

        // requestBody
        val inputType = findSuperType(input::class, Input::class)!!
        val bodyType = inputType.arguments[0].type!!
        val requestBody = if (bodyType.classifier !in listOf(Nothing::class, Unit::class)) {
            val contentType = input._contentType ?: defaultContentType
            val schema = toSchema(contentType, bodyType)
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
                        delegate.name,
                        "path",
                        delegate.required,
                        isDeprecated,
                        null,
                        toSchema(prop.returnType, default = if (delegate.required) null else delegate.default)
                    )
                    is QueryParamDelegate<*> -> Parameter(
                        delegate.name,
                        "query",
                        delegate.required,
                        isDeprecated,
                        null,
                        toSchema(prop.returnType, default = if (delegate.required) null else delegate.default)
                    )
                    is HeaderParamDelegate<*> -> Parameter(
                        delegate.name,
                        "header",
                        delegate.required,
                        isDeprecated,
                        null,
                        toSchema(prop.returnType, default = if (delegate.required) null else delegate.default)
                    )
                    else -> throw AnalysisException("Unknown delegate in class ${input::class.qualifiedName}")
                }
            }

        return Triple(deprecated, parameters, requestBody)
    }

    protected open fun analyzeResponses(route: Route, path: String): List<Response> {
        val responseTypes = route.attributes.getOrNull(ResponsesKey) ?: throw AnalysisException("There are no responses declared for path: $path")

        return responseTypes.map {
            var content: List<MediaType>? = null
            val responseBodyType = it.type ?: defaultErrorType
            if (responseBodyType != Unit::class) {
                val contentType = it.contentType ?: defaultContentType
                val schema = toSchema(contentType, responseBodyType.createType()) // FIXME: pass type
                content = listOf(MediaType(contentType = contentType.toString(), schema = schema))
            }
            val responseHeaders = defaultResponseHeaders + (it.headers ?: emptyList())
            val headers = responseHeaders.map {
                Header(it.name, it.required, it.deprecated, toSchema(it.type.createType()))
            }

            Response(it.status.value.toString(), content, it.status.description, if (headers.isEmpty()) null else headers)
        }
    }

    protected open fun toSchema(contentType: ContentType, type: KType): Schema {
        return when (contentType) {
            ContentType.Text.Plain -> Schema(null, "string", false, false, null, null, null, null, null, null, null, null)
            ContentType.Application.OctetStream -> Schema(null, "string", false, false, "binary", null, null, null, null, null, null, null)
            ContentType.Application.Json -> toSchema(type)
            else -> throw AnalysisException("Cannot transform content type $contentType to OpenAPI Schema Object: unknown transformation")
        }
    }

    protected open fun toSchema(type: KType, deprecated: Boolean = false, default: Any? = null): Schema {
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
            IntArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Int::class.createType()), null)
            LongArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Long::class.createType()), null)
            FloatArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Float::class.createType()), null)
            DoubleArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Double::class.createType()), null)
            BooleanArray::class -> return Schema(null, "array", deprecated, nullable, null, default, null, null, null, null, toSchema(Boolean::class.createType()), null)

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
            return arrayToSchema(type, classifier, deprecated, nullable)
        }

        // Lists
        if (classifier.isSubclassOf(List::class)) {
            return listToSchema(type, deprecated, nullable)
        }

        // Sets
        if (classifier.isSubclassOf(Set::class)) {
            return setToSchema(type, deprecated, nullable)
        }

        // Maps
        if (classifier.isSubclassOf(Map::class)) {
            return mapToSchema(type, classifier, deprecated, nullable)
        }

        // Enums
        if (classifier.java.isEnum) {
            return enumToSchema(classifier, deprecated, nullable, default)
        }

        // Objects
        return objectToSchema(classifier, deprecated, nullable)
    }

    protected open fun objectToSchema(classifier: KClass<*>, deprecated: Boolean, nullable: Boolean): Schema {
        val properties = classifier.memberProperties
            .asSequence()
            .filter { it.visibility == KVisibility.PUBLIC }
            .map {
                val isDeprecated = it.hasAnnotation<Deprecated>() || it.hasAnnotation<java.lang.Deprecated>()
                val schema = toSchema(it.returnType, deprecated = isDeprecated)
                Property(it.name, schema)
            }
            .toList()

        val constructorParams =
            classifier.primaryConstructor?.parameters ?: throw AnalysisException("Cannot transform type ${classifier.simpleName} to OpenAPI Schema Object: missing primary constructor")
        val required = constructorParams.asSequence()
            .filter { param ->
                properties.find { it.name == param.name }
                    ?: throw AnalysisException("Cannot transform type ${classifier.simpleName} to OpenAPI Schema Object: primary constructor param is not a public property: ${param.name}")
                !param.isOptional
            }
            .map {
                it.name!!
            }
            .toList()

        return Schema(classifier.simpleName, "object", deprecated, nullable, null, null, null, if (required.isNotEmpty()) required else null, properties, null, null, null)
    }

    protected open fun enumToSchema(classifier: KClass<*>, deprecated: Boolean, nullable: Boolean, default: Any? = null): Schema {
        val values = classifier.java.enumConstants.map { (it as Enum<*>).name }
        return Schema("${classifier.simpleName}Enum", "string", deprecated, nullable, null, default, null, null, null, null, null, values)
    }

    protected open fun mapToSchema(type: KType, classifier: KClass<*>, deprecated: Boolean, nullable: Boolean): Schema {
        val itemType = findMapValueType(type) ?: throw AnalysisException("Cannot find Map supertype with type arguments of: $type")
        val itemCls = itemType.classifier as KClass<*>
        val itemSchema = if (itemCls != Any::class) toSchema(itemType) else null
        val title = if (itemCls != Any::class) "${itemCls.simpleName}Map" else classifier.simpleName

        return Schema(title, "object", deprecated, nullable, null, null, null, null, null, itemSchema, null, null)
    }

    protected open fun setToSchema(type: KType, deprecated: Boolean, nullable: Boolean): Schema {
        val setType = findSuperType(type, AbstractSet::class)
            ?: findSuperType(type, java.util.AbstractSet::class)
            ?: findSuperType(type, Set::class)
            ?: findSuperType(type, java.util.Set::class)
            ?: throw AnalysisException("Cannot find Set supertype of: $type")
        val itemType = setType.arguments[0].type!!
        val itemCls = itemType.classifier as KClass<*>

        return Schema("${itemCls.simpleName}Set", "array", deprecated, nullable, null, null, true, null, null, null, toSchema(itemType), null)
    }

    protected open fun listToSchema(type: KType, deprecated: Boolean, nullable: Boolean): Schema {
        val listType = findSuperType(type, AbstractList::class)
            ?: findSuperType(type, java.util.AbstractList::class)
            ?: findSuperType(type, List::class)
            ?: findSuperType(type, java.util.List::class)
            ?: throw AnalysisException("Cannot find List supertype of: $type")

        val itemType = listType.arguments[0].type!!
        val itemCls = itemType.classifier as KClass<*>

        return Schema("${itemCls.simpleName}List", "array", deprecated, nullable, null, null, null, null, null, null, toSchema(itemType), null)
    }

    protected open fun arrayToSchema(type: KType, classifier: KClass<*>, deprecated: Boolean, nullable: Boolean): Schema {
        if (type.arguments.isNotEmpty()) {
            val itemType = type.arguments[0].type!!
            val itemCls = itemType.classifier as KClass<*>

            return Schema("${itemCls.simpleName}Array", "array", deprecated, nullable, null, null, null, null, null, null, toSchema(itemType), null)
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
