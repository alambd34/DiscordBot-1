/*
 * Copyright 2017 github.com/kaaz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package emily.handler;

import emily.games.AbstractGame;
import emily.games.GameState;
import emily.games.GameTurn;
import emily.guildsettings.GSetting;
import emily.main.BotConfig;
import emily.main.DiscordBot;
import emily.permission.SimpleRank;
import emily.util.DisUtil;
import emily.util.Misc;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.MessageReaction;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import org.reflections.Reflections;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GameHandler {
    //amount of invalid input attempts before auto-leaving playmode
    private static final int GAMEMODE_LEAVE_AFTER = 2;
    private static final String COMMAND_NAME = "game";
    private static final Map<String, Class<? extends AbstractGame>> gameClassMap = new HashMap<>();
    private static final Map<String, AbstractGame> gameInfoMap = new HashMap<>();
    private static boolean initialized = false;
    private final DiscordBot bot;
    private final Map<String, String> reactionMessages = new ConcurrentHashMap<>();
    private Map<String, AbstractGame> playerGames = new ConcurrentHashMap<>();
    private Map<String, String> playersToGames = new ConcurrentHashMap<>();
    private Map<String, PlayData> usersInPlayMode = new ConcurrentHashMap<>();

    public GameHandler(DiscordBot bot) {
        this.bot = bot;
    }

    public synchronized static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        Reflections reflections = new Reflections("emily.games");
        Set<Class<? extends AbstractGame>> classes = reflections.getSubTypesOf(AbstractGame.class);
        for (Class<? extends AbstractGame> gameClass : classes) {
            try {
                AbstractGame abstractGame = gameClass.getConstructor().newInstance();
                if (!abstractGame.isListed()) {
                    continue;
                }
                gameClassMap.put(abstractGame.getCodeName(), gameClass);
                gameInfoMap.put(abstractGame.getCodeName(), abstractGame);
            } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    public void cleanCache() {

        long maxAge = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30);
        Iterator<Map.Entry<String, AbstractGame>> iterator = playerGames.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AbstractGame> game = iterator.next();
            if (game.getValue().getLastTurnTimestamp() < maxAge) {
                playerGames.remove(game.getKey());
                String key = Misc.getKeyByValue(reactionMessages, game.getKey());
                if (key != null) {
                    reactionMessages.remove(key);
                }
                String otherplayer = Misc.getKeyByValue(playersToGames, game.getKey());
                if (otherplayer != null) {
                    playersToGames.remove(otherplayer);
                }
                playersToGames.remove(game.getKey());
            }
        }
    }

    public final boolean executeReaction(User player, MessageChannel channel, MessageReaction reaction, String messageId) {
        if (!channel.getType().equals(ChannelType.TEXT) || !reactionMessages.containsKey(messageId)) {
            return false;
        }
        if (!isInAGame(player.getId())) {
            return false;
        }
        if (!getGame(player.getId()).isTurnOf(player)) {
            return false;
        }
        final String input = Misc.emoteToNumber(reaction.getEmote().getName());
        Message msg = channel.getMessageById(messageId).complete();
        if (msg == null) {
            return false;
        }
        execute(player, (TextChannel) channel, input, msg);
        return true;
    }

    private boolean isInPlayMode(User user, TextChannel channel) {
        return usersInPlayMode.containsKey(user.getId()) && usersInPlayMode.get(user.getId()).getChannelId().equals(channel.getId());
    }

    private void enterPlayMode(TextChannel channel, User player) {
        usersInPlayMode.put(player.getId(), new PlayData(player.getId(), channel.getId()));
    }

    private boolean leavePlayMode(User player) {
        if (usersInPlayMode.containsKey(player.getId())) {
            usersInPlayMode.remove(player.getId());
            return true;
        }
        return false;
    }

    public boolean isGameInput(TextChannel channel, User player, String message) {
        if (GuildSettings.getFor(channel, GSetting.MODULE_GAMES).equals("true")) {
            if (isInPlayMode(player, channel) || message.startsWith(DisUtil.getCommandPrefix(channel) + COMMAND_NAME)) {
                return true;
            }
        }
        return false;
    }

    public final void execute(User player, TextChannel channel, String rawMessage, Message targetMessage) {
        String message = rawMessage.toLowerCase().trim();
        if (!isInPlayMode(player, channel)) {
            message = message.replace(DisUtil.getCommandPrefix(channel) + COMMAND_NAME, "").trim();
        }
        switch (message) {
            case "playmode":
            case "enter":
            case "play":
                enterPlayMode(channel, player);
                bot.out.sendAsyncMessage(channel, Template.get("playmode_entering_mode"));
                return;
            case "exit":
            case "leave":
            case "stop":
                if (leavePlayMode(player)) {
                    bot.out.sendAsyncMessage(channel, Template.get("playmode_leaving_mode"));
                }
                return;
            default:
                break;
        }
        String[] args = message.split(" ");
        String gameMessage = executeGameMove(args, player, channel);
        if (isInPlayMode(player, channel)) {
            gameMessage = "*note: " + Template.get("playmode_in_mode_warning") + "*" + BotConfig.EOL + gameMessage;
        } else if ("".equals(message) || "help".equals(message)) {
            gameMessage = showList(channel);
        }
        if (!gameMessage.isEmpty()) {
            if (targetMessage != null) {
                bot.queue.add(targetMessage.editMessage(gameMessage));
            } else {
                if (playerGames.containsKey(player.getId()) && playerGames.get(player.getId()).couldAddReactions()) {
                    bot.out.sendAsyncMessage(channel, gameMessage, msg -> {
                                reactionMessages.put(msg.getId(), player.getId());
                                for (String reaction : playerGames.get(player.getId()).getReactions()) {
                                    msg.addReaction(Misc.numberToEmote(Integer.parseInt(reaction))).complete();
                                }
                            }
                    );

                } else {
                    bot.out.sendAsyncMessage(channel, gameMessage);
                }
            }
        }
    }

    private String getFormattedGameList() {
        List<List<String>> table = new ArrayList<>();

        getGameList().forEach(game -> {
            List<String> row = new ArrayList<>();
            row.add(game.getCodeName());
            row.add(game.getFullname());
            table.add(row);
        });
        return Misc.makeAsciiTable(Arrays.asList("code", "gamename"), table, null);
    }

    public List<AbstractGame> getGameList() {
        List<AbstractGame> gamelist = new ArrayList<>();
        gamelist.addAll(gameInfoMap.values());
        return gamelist;
    }

    private AbstractGame createGameInstance(String gameCode) {
        try {
            return gameClassMap.get(gameCode).getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String createGame(User player, String gameCode) {
        if (!isInAGame(player.getId())) {
            if (gameClassMap.containsKey(gameCode)) {
                AbstractGame gameInstance = createGameInstance(gameCode);
                if (gameInstance == null) {
                    return Template.get("playmode_cant_create_instance");
                }
                if (createGame(player.getId(), gameInstance)) {
                    return Template.get("playmode_cant_register_instance");
                }
                gameInstance.addPlayer(player);
                if (gameInstance.waitingForPlayer()) {
                    return Template.get("playmode_created_waiting_for_player") + BotConfig.EOL + gameInstance.toString();
                }
                return gameInstance.toString();
            }
            return Template.get("playmode_invalid_gamecode");
        }
        return Template.get("playmode_already_in_game") + BotConfig.EOL + getGame(player.getId());
    }

    private String cancelGame(User player) {
        if (isInAGame(player.getId())) {
            removeGame(player.getId());
            return Template.get("playmode_canceled_game");
        }
        return Template.get("playmode_not_in_game");
    }

    private String createGamefromUserMention(TextChannel channel, User player, String theMention, String gamecode) {
        if (isInAGame(player.getId())) {
            return Template.get("playmode_already_in_game");
        }
        String userId = DisUtil.mentionToId(theMention);
        User targetUser = bot.getJda().getUserById(userId);
        if (targetUser.isBot()) {
            return Template.get("playmode_not_vs_bots");
        }
        if (targetUser.equals(player) && !bot.security.getSimpleRank(player).isAtLeast(SimpleRank.CREATOR)) {
            return Template.get("playmode_not_vs_self");
        }
        if (isInAGame(targetUser.getId())) {
            AbstractGame otherGame = getGame(targetUser.getId());
            if (otherGame != null && otherGame.waitingForPlayer()) {
                otherGame.addPlayer(player);
                otherGame.setLastPrefix(DisUtil.getCommandPrefix(channel));
                joinGame(player.getId(), targetUser.getId());
                return Template.get("playmode_joined_target") + BotConfig.EOL + otherGame.toString();
            }
            return Template.get("playmode_target_already_in_a_game");
        }
        if (!gameClassMap.containsKey(gamecode)) {
            return Template.get("playmode_invalid_gamecode");
        }

        AbstractGame newGame = createGameInstance(gamecode);
        if (newGame == null) {
            return Template.get("playmode_cant_create_instance");
        }
        createGame(player.getId(), newGame);
        newGame.addPlayer(player);
        newGame.addPlayer(targetUser);
        newGame.setLastPrefix(DisUtil.getCommandPrefix(channel));
        joinGame(targetUser.getId(), player.getId());
        return newGame.toString();
    }

    private String showHelp(TextChannel channel) {
        return showList(channel);
    }

    private String showList(TextChannel channel) {
        String prefix = DisUtil.getCommandPrefix(channel);
        return "A list of all available games" + BotConfig.EOL +
                getFormattedGameList() + BotConfig.EOL +
                "To start a game you can type `" + prefix + COMMAND_NAME + " <@user> <gamecode>`" + BotConfig.EOL + BotConfig.EOL +
                "To stop a game type `" + prefix + COMMAND_NAME + " cancel`" + BotConfig.EOL + BotConfig.EOL +
                "You can enter *gamemode* by typing `" + prefix + COMMAND_NAME + " enter` " + BotConfig.EOL +
                "This makes it so that you don't have to prefix your messages with `" + prefix + COMMAND_NAME + "`";
    }

    public String executeGameMove(String[] args, User player, TextChannel channel) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("cancel") || args[0].equalsIgnoreCase("stop")) {
                return cancelGame(player);
            } else if (args[0].equalsIgnoreCase("help")) {
                return showHelp(channel);
            } else if (args[0].equalsIgnoreCase("list")) {
                return showList(channel);
            } else if (DisUtil.isUserMention(args[0])) {
                if (args.length > 1) {
                    return createGamefromUserMention(channel, player, args[0], args[1]);
                }
                return Template.get("playmode_invalid_usage");
            } else if (args.length > 1 && DisUtil.isUserMention(args[1])) {
                return createGamefromUserMention(channel, player, args[1], args[0]);
            }
            return playTurn(player, args[0], channel);
        }
        if (isInAGame(player.getId())) {
            return String.valueOf(getGame(player.getId()));
        }
        return Template.get("playmode_not_in_game");
    }

    private String playTurn(User player, String input, TextChannel channel) {
        if (isInAGame(player.getId())) {
            AbstractGame game = getGame(player.getId());
            if (game == null) {
                return Template.get("playmode_game_corrupt");
            }
            if (game.waitingForPlayer()) {
                return Template.get("playmode_waiting_for_player");
            }
            if (!game.isTurnOf(player)) {
                return game.toString() + BotConfig.EOL + Template.get("playmode_not_your_turn");
            }
            GameTurn gameTurnInstance = game.getGameTurnInstance();
            if (gameTurnInstance == null) {
                return "BEEP BOOP CONTACT KAAZ THIS SHIT IS ON FIRE **game.getGameTurnInstance()** failed somehow";
            }
            if (!gameTurnInstance.parseInput(input)) {
                if (isInPlayMode(player, channel)) {
                    if (usersInPlayMode.get(player.getId()).failedAttempts >= GAMEMODE_LEAVE_AFTER) {
                        leavePlayMode(player);
                        return Template.get("playmode_leaving_mode");
                    }
                    usersInPlayMode.get(player.getId()).failedAttempts++;
                }
                return game.toString() + BotConfig.EOL + ":exclamation: " + gameTurnInstance.getInputErrorMessage();
            } else {
                if (isInPlayMode(player, channel)) {
                    usersInPlayMode.get(player.getId()).failedAttempts = 0;
                }
            }
            gameTurnInstance.setCommandPrefix(DisUtil.getCommandPrefix(channel));
            if (!game.isValidMove(player, gameTurnInstance)) {
                return game.toString() + BotConfig.EOL + Template.get("playmode_not_a_valid_move");
            }
            game.playTurn(player, gameTurnInstance);
            String gamestr = game.toString();
            if (game.getGameState().equals(GameState.OVER)) {
                removeGame(player.getId());
            }
            return gamestr;
        }
        return Template.get("playmode_not_in_game");
    }

    private boolean isInAGame(String playerId) {
        return playersToGames.containsKey(playerId) && playerGames.containsKey(playersToGames.get(playerId));
    }

    private boolean joinGame(String playerId, String playerHostId) {
        if (isInAGame(playerHostId)) {
            String gameId = Misc.getKeyByValue(playerGames, getGame(playerHostId));
            playersToGames.put(playerId, gameId);
        }
        return false;
    }

    private void removeGame(String playerId) {
        String gamekey = Misc.getKeyByValue(playerGames, getGame(playerId));
        playerGames.remove(gamekey);
        playersToGames.remove(playerId);
        reactionMessages.remove(gamekey);
        String otherplayer = Misc.getKeyByValue(playersToGames, gamekey);
        if (otherplayer != null) {
            playersToGames.remove(otherplayer);
        }
    }

    private AbstractGame getGame(String playerId) {
        if (isInAGame(playerId)) {
            return playerGames.get(playersToGames.get(playerId));
        }
        return null;
    }

    private boolean createGame(String playerId, AbstractGame game) {
        if (!isInAGame(playerId)) {
            playerGames.put(playerId, game);
            playersToGames.put(playerId, playerId);
            return true;
        }
        return false;
    }

    private class PlayData {
        String userId;
        int failedAttempts = 0;
        private String channelId;

        PlayData(String userId, String channelId) {
            this.userId = userId;
            this.setChannelId(channelId);
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }
    }
}
