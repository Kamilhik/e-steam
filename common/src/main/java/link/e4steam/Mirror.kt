package link.e4steam

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.PlayerList
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import java.util.function.UnaryOperator

object Mirror {
    private val LITERAL_CLASS_NAMES = arrayOf(
            "net.minecraft.text.LiteralText", // yarn
            "net.minecraft.network.chat.TextComponent",
            "net.minecraft.class_2585",
            "net.minecraft.src.C_5025_"
    )
    private val LITERAL_METHOD_NAMES = arrayOf(
            "literal",
            "method_43470",
            "m_237113_"
    )
    private val TRANSLATABLE_CLASS_NAMES = arrayOf(
            "net.minecraft.text.TranslatableText", // yarn
            "net.minecraft.network.chat.TranslatableComponent",
            "net.minecraft.class_2588",
            "net.minecraft.src.C_5026_"
    )
    private val TRANSLATABLE_METHOD_NAMES = arrayOf(
            "translatable",
            "method_43469",
            "m_237110_"
    )
    private val SUCCESS_METHOD_NAMES = arrayOf(
            "sendFeedback", // yarn
            "sendSuccess",
            "method_9226",
            "m_288197_"
    )
    private val FAILURE_METHOD_NAMES = arrayOf(
            "sendError", // yarn
            "sendFailure",
            "method_9213",
            "m_81352_"
    )
    private val WITH_STYLE_METHOD_NAMES = arrayOf(
            "styled", // yarn
            "withStyle",
            "method_27694",
            "m_130938_"
    )
    private val APPEND_METHOD_NAMES = arrayOf(
            "append",
            "method_10852",
            "m_7220_"
    )
    private val RUNCOMMAND_CLASS_NAMES = arrayOf(
            "net.minecraft.text.ClickEvent\$RunCommand", // yarn
            "net.minecraft.network.chat.ClickEvent\$RunCommand",
            "net.minecraft.class_2558\$class_10609"
    )
    private val COPYTOCLIPBOARD_CLASS_NAMES = arrayOf(
            "net.minecraft.text.ClickEvent\$CopyToClipboard", // yarn
            "net.minecraft.network.chat.ClickEvent\$CopyToClipboard",
            "net.minecraft.class_2558\$class_10606"
    )
    private val SHOWTEXT_CLASS_NAMES = arrayOf(
            "net.minecraft.text.HoverEvent\$ShowText", // yarn
            "net.minecraft.network.chat.HoverEvent\$ShowText",
            "net.minecraft.class_2568\$class_10613"
    )
    private val NAME_AND_ID_METHOD_NAMES = arrayOf(
            "nameAndId",
            "method_72498",
            "getPlayerConfigEntry"
    )
    private val IS_SINGLEPLAYER_OWNER_METHOD_NAMES = arrayOf(
            "isSingleplayerOwner",
            "method_19466",
            "m_7779_"
    )
    private val SET_USING_WHITELIST_METHOD_NAMES = arrayOf(
            "setUsingWhiteList",
            "m_6628_",
            "method_14557",
            "setWhitelistEnabled",
            "setUsingWhitelist",
            "method_73589",
    )

    @JvmStatic
    fun runCommand(command: String): ClickEvent {
        if (ClickEvent::class.java.isInterface) {
            for (className in RUNCOMMAND_CLASS_NAMES) {
                try {
                    val clazz = Class.forName(className)
                    val constructor = clazz.getConstructor(String::class.java)
                    return constructor.newInstance(command) as ClickEvent
                } catch (_: Exception) {
                }
            }
        } else {
            return ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
        }
        throw RuntimeException("Could not locate any way to make a ClickEvent!")
    }

    @JvmStatic
    fun copyToClipboard(text: String): ClickEvent {
        if (ClickEvent::class.java.isInterface) {
            for (className in COPYTOCLIPBOARD_CLASS_NAMES) {
                try {
                    val clazz = Class.forName(className)
                    val constructor = clazz.getConstructor(String::class.java)
                    return constructor.newInstance(text) as ClickEvent
                } catch (_: Exception) {
                }
            }
        } else {
            return ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text)
        }
        throw RuntimeException("Could not locate any way to make a ClickEvent!")
    }

    @JvmStatic
    fun showText(text: Component): HoverEvent {
        if (HoverEvent::class.java.isInterface) {
            for (className in SHOWTEXT_CLASS_NAMES) {
                try {
                    val clazz = Class.forName(className)
                    val constructor = clazz.getConstructor(Component::class.java)
                    return constructor.newInstance(text) as HoverEvent
                } catch (_: Exception) {
                }
            }
        } else {
            return HoverEvent(HoverEvent.Action.SHOW_TEXT, text)
        }
        throw RuntimeException("Could not locate any way to make a ClickEvent!")
    }

    @JvmStatic
    fun withStyle(component: Component, operator: UnaryOperator<Style>): Component {
        val clazz = component.javaClass
        for (methodName in WITH_STYLE_METHOD_NAMES) {
            try {
                val method = clazz.getMethod(methodName, UnaryOperator::class.java)
                return method.invoke(component, operator) as Component
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to style this Component!")
    }

    @JvmStatic
    fun append(component: Component, other: Component): Component {
        val clazz = component.javaClass
        for (methodName in APPEND_METHOD_NAMES) {
            try {
                val method = clazz.getMethod(methodName, Component::class.java)
                return method.invoke(component, other) as Component
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to append a Component to this Component!")
    }

    @JvmStatic
    fun literal(text: String): Component {
        // Try 1.18-and-older-style TextComponent initialization first
        for (className in LITERAL_CLASS_NAMES) {
            try {
                val clazz = Class.forName(className)
                val constructor = clazz.getConstructor(String::class.java)
                return constructor.newInstance(text) as Component
            } catch (_: Exception) {
            }
        }
        val clazz = Component::class.java
        for (methodName in LITERAL_METHOD_NAMES) {
            try {
                val method = clazz.getMethod(methodName, String::class.java)
                return method.invoke(null, text) as Component
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to make a literal Component!")
    }

    @JvmStatic
    fun translatable(text: String, vararg args: Any?): Component {
        // Try 1.18-and-older-style TranslatableComponent initialization first
        for (className in TRANSLATABLE_CLASS_NAMES) {
            try {
                val clazz = Class.forName(className)
                val constructor = clazz.getConstructor(String::class.java, Array<Any>::class.java)
                return constructor.newInstance(text, args as Any) as Component
            } catch (_: Exception) {
            }
        }
        val clazz = Component::class.java
        for (methodName in TRANSLATABLE_METHOD_NAMES) {
            try {
                val method = clazz.getMethod(methodName, String::class.java, Array<Any>::class.java)
                return method.invoke(null, text, args as Any) as Component
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to make a literal Component!")
    }

    @JvmStatic
    fun sendSuccessToSource(source: CommandSourceStack, message: Component) {
        sendGenericMessageToSource(source, message, SUCCESS_METHOD_NAMES)
    }

    @JvmStatic
    fun sendFailureToSource(source: CommandSourceStack, message: Component) {
        sendGenericMessageToSource(source, message, FAILURE_METHOD_NAMES)
    }

    private fun sendGenericMessageToSource(source: CommandSourceStack, message: Component, methodNames: Array<String>) {
        val clazz = CommandSourceStack::class.java
        for (methodName in methodNames) {
            try {
                val method = clazz.getMethod(methodName, Component::class.java, java.lang.Boolean.TYPE)
                method.invoke(source, message, true)
            } catch (_: Exception) {
            }
            try {
                val method = clazz.getMethod(methodName, Supplier::class.java, java.lang.Boolean.TYPE)
                method.invoke(source, Supplier<Component> { message }, true)
            } catch (_: Exception) {
            }
        }
    }

    @JvmStatic
    fun isSingleplayerOwner(server: MinecraftServer, player: ServerPlayer): Boolean {
        val clazz = ServerPlayer::class.java
        var profile: Any = player.gameProfile
        for (methodName in NAME_AND_ID_METHOD_NAMES) {
            try {
                val method = clazz.getMethod(methodName)
                profile = method.invoke(player)
            } catch (_: Exception) {
            }
        }
        val clazz2 = MinecraftServer::class.java
        for (methodName in IS_SINGLEPLAYER_OWNER_METHOD_NAMES) {
            try {
                val method = clazz2.getMethod(methodName, profile.javaClass)
                return method.invoke(server, profile) as Boolean
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to call isSingleplayerOwner!")
    }

    @JvmStatic
    fun isSingleplayerOwnerObj(server: MinecraftServer, maybeProfile: Any): Boolean {
        val clazz2 = MinecraftServer::class.java
        for (methodName in IS_SINGLEPLAYER_OWNER_METHOD_NAMES) {
            try {
                val method = clazz2.getMethod(methodName, maybeProfile.javaClass)
                return method.invoke(server, maybeProfile) as Boolean
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to call isSingleplayerOwner!")
    }

    @JvmStatic
    fun setUsingWhitelist(server: MinecraftServer, playerList: PlayerList, enabled: Boolean) {
        val clazz = MinecraftServer::class.java
        val clazz2 = PlayerList::class.java
        for (methodName in SET_USING_WHITELIST_METHOD_NAMES) {
            try {
                val method = clazz.getMethod(methodName, java.lang.Boolean.TYPE)
                method.invoke(server, enabled)
                return
            } catch (_: Exception) {
            }
            try {
                val method = clazz2.getMethod(methodName, java.lang.Boolean.TYPE)
                method.invoke(playerList, enabled)
                return
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Could not locate any way to call setUsingWhitelist!")
    }

    @JvmStatic
    fun addMessage(message: Component) {
        addMessageIf(message) { true }
    }

    @JvmStatic
    fun addMessageIf(message: Component, condition: BooleanSupplier) {
        Minecraft.getInstance().execute {
            if (!condition.asBoolean) {
                return@execute
            }
            try {
                Minecraft.getInstance().gui.chat.addMessage(message)
            } catch (_: NoSuchMethodError) {
                var chat: ChatComponent? = null
                try {
                    chat = Minecraft.getInstance().gui.chat
                } catch (_: NoSuchMethodError) {
                    try {
                        val gui = Minecraft.getInstance().gui
                        val hud = gui.javaClass.getField("hud").get(gui)
                        chat = hud.javaClass.getMethod("getChat").invoke(hud) as ChatComponent
                    } catch (_: Throwable) {
                        E4steamClient.LOGGER.error("Failed to get client chat!")
                        return@execute
                    }
                }
                try {
                    chat!!.javaClass.getMethod("addClientSystemMessage", Component::class.java)
                            .invoke(chat, message)
                } catch (_: Exception) {
                    E4steamClient.LOGGER.error("Failed to add message to client chat!")
                }
            }
        }
    }
}
