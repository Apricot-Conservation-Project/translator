package main;

import arc.*;
import arc.func.Cons;
import arc.util.CommandHandler;
import arc.util.CommandHandler.CommandResponse;
import arc.util.CommandHandler.ResponseType;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.gen.*;
import mindustry.mod.Plugin;
import mindustry.net.Administration.Config;
import mindustry.net.NetConnection;
import mindustry.net.Packets.KickReason;
import mindustry.net.ValidateException;

import com.github.benmanes.caffeine.cache.Ticker;
import com.xpdustry.flex.translator.*;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static mindustry.Vars.*;

public class TranslatorMain extends Plugin {
    static Translator t;
    static Executor executor = Executors.newCachedThreadPool();

    @Override
    public void init() {
        t = new CachingTranslator((Translator) new GoogleBasicTranslator(System.getenv("GOOGLE_API_KEY"), executor),
                executor, 1000,
                Duration.ofMinutes(10),
                Duration.ofSeconds(5), Ticker.systemTicker());
        Vars.net.handleServer(SendChatMessageCallPacket.class, (con, p) -> {
            intercept(con, p);
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.removeCommand("t");
        handler.<Player>register("t", "<message...>", "Send a message only to your teammates.", (args, player) -> {
            String message = netServer.admins.filterMessage(player, args[0]);
            if (message != null) {
                Groups.player.each(p -> p.team() == player.team(), o -> {
                    String raw = "[#" + player.team().color.toString() + "]<T> "
                            + netServer.chatFormatter.format(player, message);
                    if (o == player) {
                        o.sendMessage(raw, player, message);
                        return;
                    }
                    translate(message, o.locale(), x -> {
                        if (x.hashCode() != message.hashCode())
                            o.sendMessage(raw, player, message + " [accent](" + x + ")");
                        else
                            o.sendMessage(raw, player, message);

                    });
                });
            }
        });

    }

    static final Pattern unifier = Pattern.compile("[\\u0F80-\\u107F]{2}$");

    public String unify(String m) {
        return unifier.matcher(m).replaceFirst("");
    }

    public void translate(String msg, String to, Cons<String> when) {
        var split = to.split("_");
        var locale = new Locale(split.length == 0 ? to : split[0]);

        t.translate(msg, new Locale("auto"), locale).whenComplete((result, throwable) -> {
            Log.info("translation of @ to @ -> @ (@)", msg, to, result, throwable);
            if (result != null)
                when.get(result);
            if (throwable != null && !(throwable instanceof RateLimitedException
                    || throwable instanceof UnsupportedLanguageException)) {
                Log.err(throwable.getMessage());
            }
        });
    }

    public void intercept(NetConnection con, SendChatMessageCallPacket p) {
        var message = p.message;
        final var player = con.player;
        // do not receive chat messages from clients that are too young or not
        // registered
        if (net.server() && player != null && player.con != null && (Time.timeSinceMillis(player.con.connectTime) < 500
                || !player.con.hasConnected || !player.isAdded()))
            return;

        // detect and kick for foul play
        if (player != null && player.con != null && !player.con.chatRate.allow(2000, Config.chatSpamLimit.num())) {
            player.con.kick(KickReason.kick);
            netServer.admins.blacklistDos(player.con.address);
            return;
        }

        if (message == null)
            return;

        if (message.length() > maxTextLength) {
            throw new ValidateException(player, "Player has sent a message above the text limit.");
        }

        message = unify(message.replace("\n", ""));

        Events.fire(new PlayerChatEvent(player, message));

        // log commands before they are handled
        if (message.startsWith(netServer.clientCommands.getPrefix())) {
            // log with brackets
            Log.info("<&fi@: @&fr>", "&lk" + player.plainName(), "&lw" + message);
        }

        // check if it's a command
        CommandResponse response = netServer.clientCommands.handleMessage(message, player);
        if (response.type == ResponseType.noCommand) { // no command to handle
            final var msg = netServer.admins.filterMessage(player, message);
            // suppress chat message if it's filtered out
            if (msg == null) {
                return;
            }

            Log.info("translating for server...");
            translate(msg, "en", result -> {
                if (result.hashCode() != msg.hashCode()) {
                    Log.info("&fi@: @", "&lc" + player.plainName(),
                            "&lw" + msg + " (" + result + ")");
                } else
                    Log.info("&fi@: @", "&lc" + player.plainName(),
                            "&lw" + msg);
            });
            // server console logging
            // Log.info("&fi@: @", "&lc" + player.plainName(), "&lw" + message);

            // invoke event for all clients but also locally
            // this is required so other clients get the correct name even if they don't
            // know who's sending it yet
            Groups.player.each(ply -> {
                if (ply == player) {
                    player.sendUnformatted(player, msg);
                    return;
                }
                Log.info("translating for @", ply.plainName());
                translate(msg, ply.locale(), result -> {
                    if (result.hashCode() != msg.hashCode()) {
                        Call.sendMessage(ply.con(), netServer.chatFormatter.format(player, msg),
                                msg + " [accent](" + result + ")", player);
                    } else {
                        Call.sendMessage(ply.con(), netServer.chatFormatter.format(player, msg),
                                msg, player);
                    }
                });
            });
            // netServer.chatFormatter.format(player, message), message, player
        } else {
            // a command was sent, now get the output
            if (response.type != ResponseType.valid) {
                String text = netServer.invalidHandler.handle(player, response);
                if (text != null) {
                    player.sendMessage(text);
                }
            }
        }
    }
}
