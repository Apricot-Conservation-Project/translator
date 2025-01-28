package main;

import arc.*;
import arc.func.Cons;
import arc.struct.Seq;
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

import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
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
            String msg = unify(netServer.admins.filterMessage(player, args[0]));
            if (msg != null) {
                var locales = new HashSet<String>();
                Groups.player.each(p -> p.team() == player.team(), pl -> {
                    locales.add(locale(pl.locale()));
                });
                locales.add("en");
                String raw = "[#" + player.team().color.toString() + "]<T> "
                        + netServer.chatFormatter.format(player, msg);
                for (var e : locales) {
                    translate(msg, e, result -> {
                        var m = msg + " [accent](" + result + ")";
                        String r = "[#" + player.team().color.toString() + "]<T> "
                                + netServer.chatFormatter.format(player, m);
                        Groups.player.each(x -> x.team() == player.team() && locale(x.locale()).equals(e),
                                pl -> pl.sendMessage(r, player, m));
                    }, () -> {
                        Groups.player.each(x -> x.team() == player.team() && locale(x.locale()).equals(e),
                                pl -> pl.sendMessage(raw, player, msg));
                    });
                }
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
            .expireAfterWrite(3, TimeUnit.HOURS).expireAfterAccess(10, TimeUnit.HOURS).build();

    public String locale(String locale) {
        var x = locale.split("_");
        return (x.length == 0) ? locale : x[0];
    }

    public CompletableFuture<Void> translate(String msg, String to, Cons<String> translated, Runnable not) {
        var key = new translation(msg, to);
        var result = cache.getIfPresent(key);
        if (result != null) {
            Log.info("cache hit");
            if (result.res != null)
                translated.get(result.res);
            else
                not.run();
            return CompletableFuture.completedFuture(null);
        }
        return t.translateDetecting(msg, Translator.getAUTO_DETECT(), Locale.forLanguageTag(to)).thenAccept(res -> {
            var x = res.getText();
            var lang = res.getDetected().toLanguageTag();
            var any = false;
            for (var elem : new String[] { "es", "en", "fil", "ru", "ja",
                    "uz", "ce", "zh", "pt", "vi", "hi", "ms", "id", "de" }) {
                any |= lang.startsWith(elem);
            }
            Log.info("translation of @ (@) to @ = @", msg, lang, to, x);
            if (any & !lang.equals(to) & !msg.equals(x)) {
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

            var locales = new HashSet<String>();
            Groups.player.each(pl -> {
                locales.add(locale(pl.locale()));
            });
            locales.add("en");
            for (var e : locales) {
                translate(msg, e, result -> {
                    var m = msg + " [accent](" + result + ")";
                    var f = netServer.chatFormatter.format(player, m);
                    Groups.player.each(x -> locale(x.locale()).equals(e),
                            pl -> Call.sendMessage(pl.con(), f, m, player));
                    if (e.equals("en"))
                        Log.info("&fi@: @", "&lc" + player.plainName(),
                                "&lw" + msg + " (" + result + ")");

                }, () -> {
                    var f = netServer.chatFormatter.format(player, msg);
                    Groups.player.each(x -> locale(x.locale()).equals(e),
                            pl -> Call.sendMessage(pl.con(), f, msg, player));
                    if (e.equals("en"))
                        Log.info("&fi@: @", "&lc" + player.plainName(),
                                "&lw" + msg);
                });
            }
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
