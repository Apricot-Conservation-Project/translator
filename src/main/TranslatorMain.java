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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xpdustry.flex.translator.Translator;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static mindustry.Vars.*;

public class TranslatorMain extends Plugin {
    static final Executor executor = Executors.newCachedThreadPool();
    static Translator t = null;

    @Override
    public void init() {
        t = Translator.googleBasic(System.getenv("GOOGLE_API_KEY"), executor);
        Vars.net.handleServer(SendChatMessageCallPacket.class, this::intercept);
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
                        var m = message + " [accent](" + x + ")";
                        String r = "[#" + player.team().color.toString() + "]<T> "
                                + netServer.chatFormatter.format(player, m);
                        o.sendMessage(r, player, m);
                    }, () -> o.sendMessage(raw, player, message));
                });
            }
        });
    }

    static final Pattern unifier = Pattern.compile("[\\u0F80-\\u107F]{2}$");

    public String unify(String m) {
        return unifier.matcher(m).replaceFirst("");
    }

    record translation(String msg, String to) {
    }

    record result(String res) {
    }

    static final Cache<translation, result> cache = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(3, TimeUnit.HOURS).build();

    public void translate(String msg, String to, Cons<String> translated, Runnable not) {
        var split = to.split("_");
        var locale = split.length == 0 ? to : split[0];
        var key = new translation(msg, locale);
        var result = cache.getIfPresent(key);
        if (result != null) {
            Log.info("cache hit");
            if (result.res != null)
                translated.get(result.res);
            else
                not.run();
            return;
        }
        t.translateDetecting(msg, Translator.getAUTO_DETECT(), Locale.forLanguageTag(locale)).thenAccept(res -> {
            var x = res.getText();
            var lang = res.getDetected().toLanguageTag();
            var any = false;
            for (var elem : new String[] { "es", "en", "fil", "ru", "ja", "zh", "zh", "pt", "vi", "hi", "ms", "id" }) {
                any |= lang.startsWith(elem);
            }
            Log.info("translation of @ (@) to @ = @", msg, lang, locale, x);
            if (any & !lang.equals(locale) & !msg.equals(x)) {
                cache.put(key, new result(x));
                translated.get(x);
            } else {
                cache.put(key, new result(null));
                not.run();
            }
        });
    }

    public void intercept(NetConnection con, SendChatMessageCallPacket p) {
        var message = p.message;
        final var player = con.player;
        // do not receive chat messages from clients that are too young or not
        // registered
        if ((net.server() && player != null && player.con != null && (Time.timeSinceMillis(player.con.connectTime) < 500
                || !player.con.hasConnected || !player.isAdded())) || player == null)
            return;

        // detect and kick for foul play
        if (player.con != null && !player.con.chatRate.allow(2000, Config.chatSpamLimit.num())) {
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
                Log.info("&fi@: @", "&lc" + player.plainName(),
                        "&lw" + msg + " (" + result + ")");
            }, () -> {
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
                    // ply.sendMessage(result);
                    var m = msg + " [accent](" + result + ")";
                    Call.sendMessage(ply.con(), netServer.chatFormatter.format(player, m),
                            m, player);
                }, () -> Call.sendMessage(ply.con(), netServer.chatFormatter.format(player, msg),
                        msg, player));
            });
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
