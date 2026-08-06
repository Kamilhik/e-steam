package link.e4steam

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Comparator
import java.util.Optional
import java.util.function.Consumer

/**
 * Small reflection boundary for GUI APIs that changed after Minecraft 1.17.
 *
 * <p>The main JAR is compiled against 1.20.2, just like upstream e4steam's wide
 * compatibility build. Keeping Button.Builder and Tooltip out of bytecode
 * descriptors lets the same classes load on older Minecraft releases.</p>
 */
object MinecraftUiCompat {
    private val MESSAGE_SCREEN_CLASS_NAMES = arrayOf(
        // Mojang mappings through 1.20.6.
        "net.minecraft.client.gui.screens.GenericDirtMessageScreen",
        // Mojang mappings from 1.21 onward.
        "net.minecraft.client.gui.screens.GenericMessageScreen",
        // Stable Fabric intermediary name used across both eras.
        "net.minecraft.class_424"
    )

    private val DISCONNECT_METHOD_NAMES = arrayOf(
        "disconnect",
        "clearLevel",
        "method_18099",
        "method_18096",
        "m_91387_",
        "m_91398_"
    )

    @JvmStatic
    fun button(
        message: Component,
        onPress: Consumer<Button>,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Button {
        val fromBuilder = tryBuilder(message, onPress, x, y, width, height)
        if (fromBuilder != null) {
            return fromBuilder
        }

        val fromConstructor = tryConstructor(message, onPress, x, y, width, height)
        if (fromConstructor != null) {
            return fromConstructor
        }

        throw IllegalStateException("No compatible Minecraft Button factory was found")
    }

    /**
     * Adds a modern hover tooltip where that API exists. Minecraft 1.17-1.19.2
     * simply keeps the button without a tooltip.
     */
    @JvmStatic
    fun tooltip(widget: Any?, message: Component?) {
        if (widget == null || message == null) {
            return
        }

        for (setter in widget.javaClass.methods) {
            if (setter.parameterCount != 1 || Modifier.isStatic(setter.modifiers)) {
                continue
            }
            val tooltipType = setter.parameterTypes[0]
            val tooltip = createTooltip(tooltipType, message)
            if (tooltip == null) {
                continue
            }
            try {
                setter.invoke(widget, tooltip)
            } catch (_: ReflectiveOperationException) {
                // A tooltip is optional; the button itself remains usable.
                continue
            }
            return
        }
    }

    /**
     * Creates Minecraft's generic progress/message screen without linking the
     * caller to the Mojang class name that changed in 1.21.
     */
    @JvmStatic
    fun messageScreen(message: Component, fallback: Screen): Screen {
        for (className in MESSAGE_SCREEN_CLASS_NAMES) {
            try {
                val screenType = Class.forName(className, false, Screen::class.java.classLoader)
                if (!Screen::class.java.isAssignableFrom(screenType)) {
                    continue
                }
                for (constructor in screenType.declaredConstructors) {
                    val parameterTypes = constructor.parameterTypes
                    if (parameterTypes.size != 1
                        || !parameterTypes[0].isAssignableFrom(message.javaClass)) {
                        continue
                    }
                    constructor.isAccessible = true
                    return constructor.newInstance(message) as Screen
                }
            } catch (_: ClassNotFoundException) {
                // Try the next mapping/runtime name.
            } catch (_: ReflectiveOperationException) {
                // A progress screen is cosmetic. Keep joining via the previous
                // screen if this Minecraft version changes its constructor.
                break
            } catch (_: RuntimeException) {
                break
            }
        }
        return fallback
    }

    /**
     * Starts Minecraft's normal connection screen across its old four-argument
     * and newer five-/six-argument signatures.
     */
    @JvmStatic
    fun connect(
        parent: Screen,
        minecraft: Minecraft,
        address: ServerAddress,
        displayName: String,
        endpoint: String
    ) {
        val serverData = createServerData(displayName, endpoint)
        val methods = ConnectScreen::class.java.declaredMethods
        methods.sortWith(
            Comparator.comparingInt<Method> { method ->
                if (Modifier.isPublic(method.modifiers)) 0 else 1
            }
                .thenComparingInt { it.parameterCount }
        )
        for (method in methods) {
            if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) {
                continue
            }
            val types = method.parameterTypes
            if (!containsAssignable(types, Screen::class.java)
                || !containsAssignable(types, Minecraft::class.java)
                || !containsAssignable(types, ServerAddress::class.java)
                || !containsAssignable(types, ServerData::class.java)) {
                continue
            }

            val arguments = arrayOfNulls<Any?>(types.size)
            var valid = true
            for (i in types.indices) {
                val type = types[i]
                arguments[i] = when {
                    type.isInstance(parent) || type == Screen::class.java -> parent
                    type.isInstance(minecraft) || type == Minecraft::class.java -> minecraft
                    type.isInstance(address) || type == ServerAddress::class.java -> address
                    type.isInstance(serverData) || type == ServerData::class.java -> serverData
                    type == Boolean.TYPE -> false
                    type == Integer.TYPE -> 0
                    type == Long.TYPE -> 0L
                    type == Optional::class.java -> Optional.empty<Any>()
                    !type.isPrimitive -> null
                    else -> {
                        valid = false
                        break
                    }
                }
            }
            if (!valid) {
                continue
            }
            method.isAccessible = true
            method.invoke(null, *arguments)
            return
        }
        throw NoSuchMethodException("No compatible ConnectScreen entry point was found")
    }

    /** Disconnects the current world across the old clearLevel/new disconnect rename. */
    @JvmStatic
    fun disconnect(minecraft: Minecraft, nextScreen: Screen) {
        for (name in DISCONNECT_METHOD_NAMES) {
            for (method in Minecraft::class.java.methods) {
                if (!method.name.equals(name)
                    || Modifier.isStatic(method.modifiers)
                    || method.returnType != Void.TYPE
                    || method.parameterCount != 1
                    || !method.parameterTypes[0].isAssignableFrom(nextScreen.javaClass)) {
                    continue
                }
                method.invoke(minecraft, nextScreen)
                return
            }
        }
        throw NoSuchMethodException("No compatible Minecraft disconnect method was found")
    }

    private fun tryBuilder(
        message: Component,
        onPress: Consumer<Button>,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Button? {
        for (factory in Button::class.java.declaredMethods) {
            if (!Modifier.isStatic(factory.modifiers) || factory.parameterCount != 2) {
                continue
            }
            val parameters = factory.parameterTypes
            if (!parameters[0].isAssignableFrom(message.javaClass)
                && !parameters[0].isAssignableFrom(Component::class.java)) {
                continue
            }
            if (!parameters[1].isInterface) {
                continue
            }

            val callback = callback(parameters[1], onPress, message)
            try {
                factory.isAccessible = true
                val builder = factory.invoke(null, message, callback)
                if (builder == null || builder is Button) {
                    continue
                }

                val bounds = builder.javaClass.methods
                    .filter { !Modifier.isStatic(it.modifiers) }
                    .filter { it.parameterCount == 4 }
                    .filter { it.parameterTypes.all { type -> type == Integer.TYPE } }
                    .filter { method ->
                        method.returnType.isAssignableFrom(builder.javaClass)
                            || builder.javaClass.isAssignableFrom(method.returnType)
                    }
                    .firstOrNull()
                if (bounds == null) {
                    continue
                }
                bounds.invoke(builder, x, y, width, height)

                val build = builder.javaClass.methods
                    .filter { !Modifier.isStatic(it.modifiers) }
                    .filter { it.parameterCount == 0 }
                    .filter { Button::class.java.isAssignableFrom(it.returnType) }
                    .firstOrNull()
                if (build != null) {
                    return build.invoke(builder) as Button
                }
            } catch (_: ReflectiveOperationException) {
                // Fall through to the pre-builder constructor path.
            } catch (_: RuntimeException) {
                // Fall through to the pre-builder constructor path.
            }
        }
        return null
    }

    private fun createServerData(displayName: String, endpoint: String): Any {
        val constructors = ServerData::class.java.declaredConstructors
        constructors.sortWith(Comparator.comparingInt<Constructor<*>> { it.parameterCount })
        var lastFailure: ReflectiveOperationException? = null
        for (constructor in constructors) {
            val types = constructor.parameterTypes
            if (types.size < 2 || types[0] != String::class.java || types[1] != String::class.java) {
                continue
            }
            val arguments = arrayOfNulls<Any?>(types.size)
            arguments[0] = displayName
            arguments[1] = endpoint
            for (i in 2 until types.size) {
                val type = types[i]
                arguments[i] = when {
                    type == Boolean.TYPE -> false
                    type == Integer.TYPE -> 0
                    type == Long.TYPE -> 0L
                    type.isEnum -> {
                        val constants = type.enumConstants!!
                        var selected: Any? = null
                        for (constant in constants) {
                            if ((constant as Enum<*>).name == "OTHER") {
                                selected = constant
                                break
                            }
                        }
                        selected ?: (if (constants.isEmpty()) null else constants[0])
                    }
                    type == Optional::class.java -> Optional.empty<Any>()
                    else -> null
                }
            }
            try {
                constructor.isAccessible = true
                return constructor.newInstance(*arguments)
            } catch (failure: ReflectiveOperationException) {
                lastFailure = failure
            }
        }
        if (lastFailure != null) {
            throw lastFailure!!
        }
        throw NoSuchMethodException("No compatible ServerData constructor was found")
    }

    private fun containsAssignable(haystack: Array<Class<*>>, needle: Class<*>): Boolean {
        for (type in haystack) {
            if (type != Any::class.java
                && (type.isAssignableFrom(needle) || needle.isAssignableFrom(type))) {
                return true
            }
        }
        return false
    }

    private fun tryConstructor(
        message: Component,
        onPress: Consumer<Button>,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Button? {
        val constructors = Button::class.java.declaredConstructors
        constructors.sortWith(Comparator.comparingInt<Constructor<*>> { it.parameterCount })
        for (constructor in constructors) {
            val parameterTypes = constructor.parameterTypes
            if (parameterTypes.size < 6
                || parameterTypes[0] != Integer.TYPE
                || parameterTypes[1] != Integer.TYPE
                || parameterTypes[2] != Integer.TYPE
                || parameterTypes[3] != Integer.TYPE
                || !Component::class.java.isAssignableFrom(parameterTypes[4])
                || !parameterTypes[5].isInterface) {
                continue
            }

            val arguments = arrayOfNulls<Any?>(parameterTypes.size)
            arguments[0] = x
            arguments[1] = y
            arguments[2] = width
            arguments[3] = height
            arguments[4] = message
            arguments[5] = callback(parameterTypes[5], onPress, message)
            for (i in 6 until parameterTypes.size) {
                arguments[i] = defaultValue(parameterTypes[i], message)
            }

            try {
                constructor.isAccessible = true
                return constructor.newInstance(*arguments) as Button
            } catch (_: ReflectiveOperationException) {
                // Try the next constructor shape.
            } catch (_: RuntimeException) {
                // Try the next constructor shape.
            }
        }
        return null
    }

    private fun createTooltip(tooltipType: Class<*>, message: Component): Any? {
        for (method in tooltipType.declaredMethods) {
            if (!Modifier.isStatic(method.modifiers)
                || method.parameterCount != 1
                || !tooltipType.isAssignableFrom(method.returnType)
                || !method.parameterTypes[0].isAssignableFrom(message.javaClass)) {
                continue
            }
            try {
                method.isAccessible = true
                return method.invoke(null, message)
            } catch (_: ReflectiveOperationException) {
                // Try another factory or constructor.
            } catch (_: RuntimeException) {
                // Try another factory or constructor.
            }
        }
        for (constructor in tooltipType.declaredConstructors) {
            val parameterTypes = constructor.parameterTypes
            if (parameterTypes.size == 1
                && parameterTypes[0].isAssignableFrom(message.javaClass)) {
                try {
                    constructor.isAccessible = true
                    return constructor.newInstance(message)
                } catch (_: ReflectiveOperationException) {
                    return null
                } catch (_: RuntimeException) {
                    return null
                }
            }
        }
        return null
    }

    private fun callback(callbackType: Class<*>, onPress: Consumer<Button>, message: Component): Any {
        return Proxy.newProxyInstance(
            callbackType.classLoader,
            arrayOf<Class<*>>(callbackType),
            InvocationHandler { proxy, method, args ->
                if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "toString" -> "e4steam Steam button callback"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy == args?.getOrNull(0)
                        else -> null
                    }
                } else {
                    if (args != null) {
                        for (argument in args) {
                            if (argument is Button) {
                                onPress.accept(argument)
                                break
                            }
                        }
                    }
                    defaultValue(method.returnType, message)
                }
            }
        )
    }

    private fun defaultValue(type: Class<*>, message: Component): Any? {
        if (!type.isPrimitive) {
            if (type.isAssignableFrom(message.javaClass) || type.isAssignableFrom(Component::class.java)) {
                return message
            }
            if (type.isInterface) {
                return Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf<Class<*>>(type),
                    InvocationHandler { _, method, _ -> defaultValue(method.returnType, message) }
                )
            }
            return null
        }
        return when (type) {
            Boolean.TYPE -> false
            Character.TYPE -> '\u0000'
            Byte.TYPE -> 0.toByte()
            Short.TYPE -> 0.toShort()
            Integer.TYPE -> 0
            Long.TYPE -> 0L
            Float.TYPE -> 0F
            Double.TYPE -> 0D
            else -> null
        }
    }
}
