package app.hushmessenger.xposed.dex

import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * A classloader-neutral method descriptor.
 *
 * Encoding: `binary.ClassName|methodName|p1,p2,...|returnType`
 *
 * Types are JVM binary names (`java.lang.String`, `boolean`, `void`). The
 * descriptor is cached in Messenger's data directory and resolved reflectively
 * after the host classloader is ready.
 */
data class MethodRef(
    val className: String,
    val name: String,
    val params: List<String>,
    val returnType: String,
) {

    fun encode(): String = "$className|$name|${params.joinToString(",")}|$returnType"

    fun toMethod(classLoader: ClassLoader): Method? = runCatching {
        val clazz = Class.forName(className, false, classLoader)
        val types = params.map { typeOf(it, classLoader) }.toTypedArray()
        clazz.getDeclaredMethod(name, *types).apply { isAccessible = true }
    }.getOrNull()

    override fun toString(): String = encode()

    companion object {

        fun decode(value: String): MethodRef? {
            val parts = value.split('|', limit = 4)
            if (parts.size != 4) return null
            val className = parts[0].trim()
            val name = parts[1].trim()
            if (className.isEmpty() || name.isEmpty()) return null
            return MethodRef(
                className = className,
                name = name,
                params = parts[2].split(',').map { it.trim() }.filter { it.isNotEmpty() },
                returnType = parts[3].trim(),
            )
        }

        private fun typeOf(name: String, classLoader: ClassLoader): Class<*> = when {
            name.endsWith("[]") ->
                java.lang.reflect.Array.newInstance(typeOf(name.dropLast(2), classLoader), 0).javaClass

            name == "void" -> Void.TYPE
            name == "boolean" -> Boolean::class.javaPrimitiveType!!
            name == "byte" -> Byte::class.javaPrimitiveType!!
            name == "char" -> Char::class.javaPrimitiveType!!
            name == "short" -> Short::class.javaPrimitiveType!!
            name == "int" -> Int::class.javaPrimitiveType!!
            name == "long" -> Long::class.javaPrimitiveType!!
            name == "float" -> Float::class.javaPrimitiveType!!
            name == "double" -> Double::class.javaPrimitiveType!!
            else -> Class.forName(name, false, classLoader)
        }
    }
}

fun MethodData.toMethodRef(): MethodRef = MethodRef(
    className = className,
    name = name,
    params = paramTypeNames,
    returnType = returnTypeName,
)
