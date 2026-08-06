package link.e4steam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Small reflection boundary for GUI APIs that changed after Minecraft 1.17.
 *
 * <p>The main JAR is compiled against 1.20.2, just like upstream e4steam's wide
 * compatibility build. Keeping Button.Builder and Tooltip out of bytecode
 * descriptors lets the same classes load on older Minecraft releases.</p>
 */
public final class MinecraftUiCompat {
    private static final String[] MESSAGE_SCREEN_CLASS_NAMES = {
            // Mojang mappings through 1.20.6.
            "net.minecraft.client.gui.screens.GenericDirtMessageScreen",
            // Mojang mappings from 1.21 onward.
            "net.minecraft.client.gui.screens.GenericMessageScreen",
            // Stable Fabric intermediary name used across both eras.
            "net.minecraft.class_424"
    };

    private static final String[] DISCONNECT_METHOD_NAMES = {
            "disconnect",
            "clearClientLevel",
            "clearLevel",
            "method_18099",
            "method_18096",
            "m_91387_",
            "m_91398_"
    };

    private MinecraftUiCompat() {
    }

    public static Button button(
            Component message,
            Consumer<Button> onPress,
            int x,
            int y,
            int width,
            int height
    ) {
        Button fromBuilder = tryBuilder(message, onPress, x, y, width, height);
        if (fromBuilder != null) {
            return fromBuilder;
        }

        Button fromConstructor = tryConstructor(message, onPress, x, y, width, height);
        if (fromConstructor != null) {
            return fromConstructor;
        }

        throw new IllegalStateException("No compatible Minecraft Button factory was found");
    }

    /**
     * Adds a modern hover tooltip where that API exists. Minecraft 1.17-1.19.2
     * simply keeps the button without a tooltip.
     */
    public static void tooltip(Object widget, Component message) {
        if (widget == null || message == null) {
            return;
        }

        for (Method setter : widget.getClass().getMethods()) {
            if (setter.getParameterCount() != 1 || Modifier.isStatic(setter.getModifiers())) {
                continue;
            }
            Class<?> tooltipType = setter.getParameterTypes()[0];
            Object tooltip = createTooltip(tooltipType, message);
            if (tooltip == null) {
                continue;
            }
            try {
                setter.invoke(widget, tooltip);
            } catch (ReflectiveOperationException ignored) {
                // A tooltip is optional; the button itself remains usable.
                continue;
            }
            return;
        }
    }

    /**
     * Creates Minecraft's generic progress/message screen without linking the
     * caller to the Mojang class name that changed in 1.21.
     */
    public static Screen messageScreen(Component message, Screen fallback) {
        for (String className : MESSAGE_SCREEN_CLASS_NAMES) {
            try {
                Class<?> screenType = Class.forName(className, false, Screen.class.getClassLoader());
                if (!Screen.class.isAssignableFrom(screenType)) {
                    continue;
                }
                for (Constructor<?> constructor : screenType.getDeclaredConstructors()) {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    if (parameterTypes.length != 1
                            || !parameterTypes[0].isAssignableFrom(message.getClass())) {
                        continue;
                    }
                    constructor.setAccessible(true);
                    return (Screen) constructor.newInstance(message);
                }
            } catch (ClassNotFoundException ignored) {
                // Try the next mapping/runtime name.
            } catch (ReflectiveOperationException | RuntimeException failure) {
                // A progress screen is cosmetic. Keep joining via the previous
                // screen if this Minecraft version changes its constructor.
                break;
            }
        }
        return fallback;
    }

    /**
     * Starts Minecraft's normal connection screen across its old four-argument
     * and newer five-/six-argument signatures.
     */
    public static void connect(
            Screen parent,
            Minecraft minecraft,
            ServerAddress address,
            String displayName,
            String endpoint
    ) throws ReflectiveOperationException {
        Object serverData = createServerData(displayName, endpoint);
        Method[] methods = ConnectScreen.class.getDeclaredMethods();
        Arrays.sort(methods, Comparator
                .<Method>comparingInt(method -> Modifier.isPublic(method.getModifiers()) ? 0 : 1)
                .thenComparingInt(Method::getParameterCount));
        for (Method method : methods) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (!containsAssignable(types, Screen.class)
                    || !containsAssignable(types, Minecraft.class)
                    || !containsAssignable(types, ServerAddress.class)
                    || !containsAssignable(types, ServerData.class)) {
                continue;
            }

            Object[] arguments = new Object[types.length];
            boolean valid = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type.isInstance(parent) || type == Screen.class) {
                    arguments[i] = parent;
                } else if (type.isInstance(minecraft) || type == Minecraft.class) {
                    arguments[i] = minecraft;
                } else if (type.isInstance(address) || type == ServerAddress.class) {
                    arguments[i] = address;
                } else if (type.isInstance(serverData) || type == ServerData.class) {
                    arguments[i] = serverData;
                } else if (type == boolean.class) {
                    arguments[i] = false;
                } else if (type == int.class) {
                    arguments[i] = 0;
                } else if (type == long.class) {
                    arguments[i] = 0L;
                } else if (type == Optional.class) {
                    arguments[i] = Optional.empty();
                } else if (!type.isPrimitive()) {
                    arguments[i] = null;
                } else {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                continue;
            }
            method.setAccessible(true);
            method.invoke(null, arguments);
            return;
        }
        throw new NoSuchMethodException("No compatible ConnectScreen entry point was found");
    }

    /** Disconnects the current world across the old clearLevel/new disconnect rename. */
    public static void disconnect(Minecraft minecraft, Screen nextScreen)
            throws ReflectiveOperationException {
        for (String name : DISCONNECT_METHOD_NAMES) {
            for (Method method : Minecraft.class.getMethods()) {
                if (!method.getName().equals(name)
                        || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class
                        || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(nextScreen.getClass())) {
                    continue;
                }
                method.invoke(minecraft, nextScreen);
                return;
            }
        }
        throw new NoSuchMethodException("No compatible Minecraft disconnect method was found");
    }

    /**
     * Returns the currently displayed screen. Minecraft 26.2 moved the current
     * screen out of {@code Minecraft.screen} and into {@code Gui#screen()}; the
     * compiled field reference below stays valid everywhere up to 26.1.
     */
    public static Screen currentScreen(Minecraft minecraft) {
        try {
            Field screenField = Minecraft.class.getDeclaredField("screen");
            screenField.setAccessible(true);
            return (Screen) screenField.get(minecraft);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 26.2 removed the field; ask Gui instead.
        }
        try {
            Field guiField = Minecraft.class.getDeclaredField("gui");
            guiField.setAccessible(true);
            Object gui = guiField.get(minecraft);
            if (gui != null) {
                for (Method method : gui.getClass().getMethods()) {
                    if (method.getParameterCount() == 0
                            && !Modifier.isStatic(method.getModifiers())
                            && Screen.class.isAssignableFrom(method.getReturnType())) {
                        return (Screen) method.invoke(gui);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall back to the compiled screen field below.
        }
        return minecraft.screen;
    }

    /**
     * Sets the displayed screen. Minecraft 26.2 moved {@code setScreen} from
     * {@code Minecraft} to {@code Gui#setScreen(Screen)}; the compiled call
     * below stays valid everywhere up to 26.1.
     */
    public static void setCurrentScreen(Minecraft minecraft, Screen screen) {
        try {
            Field guiField = Minecraft.class.getDeclaredField("gui");
            guiField.setAccessible(true);
            Object gui = guiField.get(minecraft);
            if (gui != null) {
                for (Method method : gui.getClass().getMethods()) {
                    if (method.getParameterCount() == 1
                            && !Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == void.class) {
                        Class<?> parameterType = method.getParameterTypes()[0];
                        if (parameterType != Object.class
                                && parameterType.isAssignableFrom(Screen.class)) {
                            method.invoke(gui, screen);
                            return;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall back to the compiled setScreen call below.
        }
        minecraft.setScreen(screen);
    }

    private static Button tryBuilder(
            Component message,
            Consumer<Button> onPress,
            int x,
            int y,
            int width,
            int height
    ) {
        for (Method factory : Button.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers()) || factory.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] parameters = factory.getParameterTypes();
            if (!parameters[0].isAssignableFrom(message.getClass())
                    && !parameters[0].isAssignableFrom(Component.class)) {
                continue;
            }
            if (!parameters[1].isInterface()) {
                continue;
            }

            Object callback = callback(parameters[1], onPress, message);
            try {
                factory.setAccessible(true);
                Object builder = factory.invoke(null, message, callback);
                if (builder == null || builder instanceof Button) {
                    continue;
                }

                Method bounds = Arrays.stream(builder.getClass().getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getParameterCount() == 4)
                        .filter(method -> Arrays.stream(method.getParameterTypes())
                                .allMatch(type -> type == int.class))
                        .filter(method -> method.getReturnType().isAssignableFrom(builder.getClass())
                                || builder.getClass().isAssignableFrom(method.getReturnType()))
                        .findFirst()
                        .orElse(null);
                if (bounds == null) {
                    continue;
                }
                bounds.invoke(builder, x, y, width, height);

                Method build = Arrays.stream(builder.getClass().getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getParameterCount() == 0)
                        .filter(method -> Button.class.isAssignableFrom(method.getReturnType()))
                        .findFirst()
                        .orElse(null);
                if (build != null) {
                    return (Button) build.invoke(builder);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the pre-builder constructor path.
            }
        }
        return null;
    }

    private static Object createServerData(String displayName, String endpoint)
            throws ReflectiveOperationException {
        Constructor<?>[] constructors = ServerData.class.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        ReflectiveOperationException lastFailure = null;
        for (Constructor<?> constructor : constructors) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length < 2 || types[0] != String.class || types[1] != String.class) {
                continue;
            }
            Object[] arguments = new Object[types.length];
            arguments[0] = displayName;
            arguments[1] = endpoint;
            for (int i = 2; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == boolean.class) {
                    arguments[i] = false;
                } else if (type == int.class) {
                    arguments[i] = 0;
                } else if (type == long.class) {
                    arguments[i] = 0L;
                } else if (type.isEnum()) {
                    Object[] constants = type.getEnumConstants();
                    Object selected = null;
                    for (Object constant : constants) {
                        if (((Enum<?>) constant).name().equals("OTHER")) {
                            selected = constant;
                            break;
                        }
                    }
                    arguments[i] = selected != null
                            ? selected
                            : (constants.length == 0 ? null : constants[0]);
                } else if (type == Optional.class) {
                    arguments[i] = Optional.empty();
                } else {
                    arguments[i] = null;
                }
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException failure) {
                lastFailure = failure;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new NoSuchMethodException("No compatible ServerData constructor was found");
    }

    private static boolean containsAssignable(Class<?>[] haystack, Class<?> needle) {
        for (Class<?> type : haystack) {
            if (type != Object.class
                    && (type.isAssignableFrom(needle) || needle.isAssignableFrom(type))) {
                return true;
            }
        }
        return false;
    }

    private static Button tryConstructor(
            Component message,
            Consumer<Button> onPress,
            int x,
            int y,
            int width,
            int height
    ) {
        Constructor<?>[] constructors = Button.class.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length < 6
                    || parameterTypes[0] != int.class
                    || parameterTypes[1] != int.class
                    || parameterTypes[2] != int.class
                    || parameterTypes[3] != int.class
                    || !Component.class.isAssignableFrom(parameterTypes[4])
                    || !parameterTypes[5].isInterface()) {
                continue;
            }

            Object[] arguments = new Object[parameterTypes.length];
            arguments[0] = x;
            arguments[1] = y;
            arguments[2] = width;
            arguments[3] = height;
            arguments[4] = message;
            arguments[5] = callback(parameterTypes[5], onPress, message);
            for (int i = 6; i < parameterTypes.length; i++) {
                arguments[i] = defaultValue(parameterTypes[i], message);
            }

            try {
                constructor.setAccessible(true);
                return (Button) constructor.newInstance(arguments);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next constructor shape.
            }
        }
        return null;
    }

    private static Object createTooltip(Class<?> tooltipType, Component message) {
        for (Method method : tooltipType.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || !tooltipType.isAssignableFrom(method.getReturnType())
                    || !method.getParameterTypes()[0].isAssignableFrom(message.getClass())) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(null, message);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try another factory or constructor.
            }
        }
        for (Constructor<?> constructor : tooltipType.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 1
                    && parameterTypes[0].isAssignableFrom(message.getClass())) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(message);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object callback(Class<?> callbackType, Consumer<Button> onPress, Component message) {
        return Proxy.newProxyInstance(
                callbackType.getClassLoader(),
                new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "e4steam Steam button callback";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == (args == null ? null : args[0]);
                            default -> null;
                        };
                    }
                    if (args != null) {
                        for (Object argument : args) {
                            if (argument instanceof Button button) {
                                onPress.accept(button);
                                break;
                            }
                        }
                    }
                    return defaultValue(method.getReturnType(), message);
                }
        );
    }

    private static Object defaultValue(Class<?> type, Component message) {
        if (!type.isPrimitive()) {
            if (type.isAssignableFrom(message.getClass()) || type.isAssignableFrom(Component.class)) {
                return message;
            }
            if (type.isInterface()) {
                return Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[]{type},
                        (proxy, method, args) -> defaultValue(method.getReturnType(), message)
                );
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }
}
