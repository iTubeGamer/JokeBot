package de.maxkroner.implementation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.pmw.tinylog.Logger;

import de.maxkroner.implementation.runnable.CheckTempChannelRunnable;
import de.maxkroner.model.TempChannel;
import de.maxkroner.model.TempChannelMap;
import de.maxkroner.parsing.Command;
import de.maxkroner.parsing.CommandHandler;
import de.maxkroner.parsing.CommandOption;
import de.maxkroner.parsing.OptionParsing;
import de.maxkroner.to.TempChannelTO;
import de.maxkroner.ui.TempChannelMenue;
import de.maxkroner.values.Keys;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.ReadyEvent;
import sx.blah.discord.handle.impl.events.guild.GuildCreateEvent;
import sx.blah.discord.handle.impl.events.guild.GuildLeaveEvent;
import sx.blah.discord.handle.impl.events.guild.GuildUnavailableEvent;
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent;
import sx.blah.discord.handle.impl.events.guild.voice.VoiceChannelDeleteEvent;
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelJoinEvent;
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelLeaveEvent;
import sx.blah.discord.handle.impl.events.guild.voice.user.UserVoiceChannelMoveEvent;
import sx.blah.discord.handle.obj.ICategory;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IGuild;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.handle.obj.IVoiceChannel;
import sx.blah.discord.handle.obj.Permissions;
import sx.blah.discord.util.MessageBuilder;
import sx.blah.discord.util.MissingPermissionsException;

public class TempChannelBot extends Bot {
	private static final int timeout_for_unknown_channels = 5;
	private static HashMap<IGuild, TempChannelMap> tempChannelsByGuild = new HashMap<>();
	private static ArrayList<TempChannel> stashedChannels = new ArrayList<>(); // if bot leaves Guild tempChannels get stashed
	private static final EnumSet<Permissions> voice_connect = EnumSet.of(Permissions.VOICE_CONNECT);
	private static final EnumSet<Permissions> empty = EnumSet.noneOf(Permissions.class);
	private static final int USER_CHANNEL_LIMIT = 3;
	private static String path_serialized_tempChannels = "~/discordBots/TempChannels/temp/";
	private static final String file_name = "tempChannels.ser";
	private static String home = "";
	private static boolean still_in_startup_mode = true;

	public TempChannelBot(String token) {
		super("TempChannels");
		super.addConsoleMenue(new TempChannelMenue(this, tempChannelsByGuild));
		home = System.getProperty("user.home");
		path_serialized_tempChannels = Paths.get(home, "discordBots", "TempChannels", "tmp").toString();
		addCommandParsing(this.getClass());
	}

	@Override
	public void disconnect() {
		saveTempChannel();
	}

	// ----- EVENT HANDLING ----- //
	@Override
	@EventSubscriber
	public void onReady(ReadyEvent event) {
		super.onReady(event);

		// create tempChannelMaps
		for (IGuild guild : getClient().getGuilds()) {
			if (!tempChannelsByGuild.containsKey(guild)) {
				TempChannelMap tempChannelMap = new TempChannelMap();
				tempChannelsByGuild.put(guild, tempChannelMap);
			}
		}

		// import previous TempChannels from file
		readTempChannelsFromFile();

		// delete Channel which aren't existent in map
		removeUnkownChannelsForGuild(tempChannelsByGuild.keySet());

		// start Channel-Timout Scheduler
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
		CheckTempChannelRunnable<Runnable> checkEvent = new CheckTempChannelRunnable<Runnable>(tempChannelsByGuild, getClient(), executor);
		executor.scheduleAtFixedRate(checkEvent, 1, 1, TimeUnit.MINUTES);
		Logger.info("TempChannels startet up and ready 2 go!");
		still_in_startup_mode = false;
	}

	@EventSubscriber
	public void onGuildCreateEvent(GuildCreateEvent event) {
		// if the bot is added to a new guild, add guild to map
		IGuild guild = event.getGuild();
		Logger.info("Received GuildCreateEvent for guild {}", guild.getName());
		if (tempChannelsByGuild != null && !still_in_startup_mode) {
			if (!tempChannelsByGuild.containsKey(guild)) {
				TempChannelMap tempChannelMap = new TempChannelMap();
				tempChannelsByGuild.put(guild, tempChannelMap);
				importStashedChannelsForGuild(guild);
				removeUnknownChannelsForGuild(guild);	
			}	
			updateGuildCount(getClient().getGuilds().size(), Keys.discordbotsorgToken, Keys.botId);
		}	
	}

	@EventSubscriber
	public void onGuildLeaveEvent(GuildLeaveEvent event) {
		IGuild guild = event.getGuild();
		Logger.warn("Received GuildLeaveEvent for guild {}", guild.getName());
		stashChannelsAndRemoveMap(guild);
	}

	@EventSubscriber
	public void onGuildUnavailableEvent(GuildUnavailableEvent event) {
		IGuild guild = event.getGuild();
		if(guild != null) {
			Logger.warn("Received GuildUnavailableEvent for guild {}", guild.getName());
			stashChannelsAndRemoveMap(guild);	
		}	
	}

	@EventSubscriber
	public void onVoiceChannelDelete(VoiceChannelDeleteEvent event) {
		// get Channel that got deleted
		IChannel deletedChannel = event.getVoiceChannel();

		// get TempChannel for the Channel
		TempChannelMap tempChannelMap = tempChannelsByGuild.get(event.getGuild());
		if(deletedChannel != null) {
			TempChannel tempChannelToRemove = tempChannelMap.getTempChannelForChannel(deletedChannel);

			// delete if TempChannel exists
			if (tempChannelToRemove != null) {
				Logger.info("Removing TempChannel {} from map!", deletedChannel.getName());

				tempChannelMap.removeTempChannel(tempChannelToRemove);
			}
		}
		
	}

	@Override
	@EventSubscriber
	public void onMessageReceivedEvent(MessageReceivedEvent event) {

		if (!event.getChannel().isPrivate()) {
			super.onMessageReceivedEvent(event);
		} else {
			sendMessage(
					"Hey, I'd like to chat with you too, because you are very cute :smile: But unfortunately I am only a bot and therefore I am very akward with strangers...",
					event.getChannel(), false);
			sendMessage("But if you want to send me a command please do it in a server text channel, ok?", event.getChannel(), false);
			return;
		}

	}

	@EventSubscriber
	public void onUserVoiceChannelJoin(UserVoiceChannelJoinEvent event) {
		setEmptyMinutesToZero(event.getVoiceChannel());
	}

	@EventSubscriber
	public void onUserVoiceChannelMove(UserVoiceChannelMoveEvent event) {
		setEmptyMinutesToZero(event.getNewChannel());
		deleteIfKickOrBanChannel(event.getOldChannel());
	}
	
	@EventSubscriber
	public void onUserVoiceChannelLeave(UserVoiceChannelLeaveEvent event) {
		deleteIfKickOrBanChannel(event.getVoiceChannel());
	}

	// ----- COMMAND HANDLING ----- //

	@CommandHandler({"create", "c", "new"})
	protected void executeChannelCommand(MessageReceivedEvent event, Command command) {
		String name = null;
		List<IUser> allowedUsers = null; // null = everyone allowed in the new channel
		List<IUser> movePlayers = new ArrayList<IUser>(); // players to move in the new channel
		int limit = 0;
		int timeout = 5;
		boolean moveAllowedPlayers = false;

		List<String> errorMessages = new ArrayList<>();

		if (checkIfPrequisitesAreMet(event.getChannel(), event.getAuthor(), event.getGuild(), errorMessages)) {
			for (CommandOption option : command.getCommandOptions().orElse(Collections.emptyList())) {

				switch (option.getCommandOptionName()) {
				case "p":
					allowedUsers = OptionParsing.parsePrivateOption(option, event, getClient());
					break;
				case "m":
					movePlayers = OptionParsing.parseMoveOption(option, event, getClient());
					if (movePlayers == null) {
						moveAllowedPlayers = true;
					}
					break;
				case "n":
					name = OptionParsing.parseNameOption(event.getChannel(), name, option, errorMessages);
					break;
				case "l":
					limit = OptionParsing.parseLimitOption(event.getChannel(), limit, option, errorMessages);
					break;
				case "t":
					timeout = OptionParsing.parseTimoutOption(event.getChannel(), timeout, option, errorMessages);
					break;
				}
			}

			// if all the players that are allowed in the channel (-p) should be moved, add them to the move list
			if (moveAllowedPlayers) {
				movePlayers = new ArrayList<IUser>();
				movePlayers.addAll(allowedUsers);
				movePlayers.add(event.getAuthor());
			}
			
			//if no name was given create fitting name
			if(name == null){
				name = createChannelName(event.getAuthor(), event.getGuild());
			}		
		}

		if (errorMessages.isEmpty()) {
			createTempChannel(event.getGuild(), event.getAuthor(), name, allowedUsers, movePlayers, limit, timeout, false);
		} else {
			sendErrorMessages(event.getChannel(), event.getAuthor(), errorMessages, event.getMessage().getContent());
		}
	}

	@CommandHandler({"clear", "cc"})
	protected void executeChannelClearCommand(MessageReceivedEvent event, Command command) {
		IGuild guild = event.getGuild();
		IUser author = event.getAuthor();
		IChannel channel = event.getChannel();

		// if user has no channels we're done
		if (getUserChannelCountOnGuild(author, guild) == 0) {
			Logger.info("User {} had no tempChannels", event.getAuthor());
			sendMessage(event.getAuthor() + ", you have no temporary channels at the moment!", channel, false);
			return;
		}

		// else, get all user channels
		Collection<TempChannel> userChannels = getUserChannelsOnGuild(author, guild);

		// parse option -f
		boolean forceDelete = false;
		for (CommandOption option : command.getCommandOptions().orElse(Collections.emptyList())) {
			switch (option.getCommandOptionName()) {
			case "f":
				forceDelete = true;
				break;
			}
		}

		// delete the channels
		boolean sendMessage = false;
		for (Iterator<TempChannel> iterator = userChannels.iterator(); iterator.hasNext();) {
			IVoiceChannel userChannel = iterator.next().getChannel();
			if (userChannel.getConnectedUsers().isEmpty()) {
				Logger.info("Deleting empty channel {}", userChannel.getName());
				userChannel.delete();
			} else {
				if (forceDelete) {
					Logger.info("Force-Deleting channel {}", userChannel.getName());
					userChannel.delete();
				} else {
					Logger.info("Channel {} isn't empty", userChannel.getName());
					sendMessage = true;
				}
			}
		}

		if (sendMessage) {
			sendMessage("Some of your channels arent empty. Use `!cc -f` to force the deletion anyway.", channel, false);
		}

	}

	@CommandHandler({"kick", "k"})
	protected void executeKickCommand(MessageReceivedEvent event, Command command) {
		IVoiceChannel channelToKickFrom = event.getAuthor().getVoiceStateForGuild(event.getGuild()).getChannel();
		TempChannel tempChannelToKickFrom = tempChannelsByGuild.get(event.getGuild()).getTempChannelForChannel(channelToKickFrom);
		if(tempChannelToKickFrom != null && event.getAuthor().equals(tempChannelToKickFrom.getOwner())){
			List<IUser> usersToKick = OptionParsing.parseUserList(command.getArguments().orElse(Collections.emptyList()), getClient());
			if(usersToKick.remove(event.getAuthor())){
				sendMessage("You tried to kick yourself from your own TempChannel :scream:", event.getAuthor().getOrCreatePMChannel(), false);
			}
			//filter users who arent in the channel
			usersToKick = usersToKick.stream()
					.filter(T -> T.getVoiceStateForGuild(event.getGuild()).getChannel() != null)
					.filter(T -> T.getVoiceStateForGuild(event.getGuild()).getChannel().equals(channelToKickFrom)).collect(Collectors.toList());
			if(!usersToKick.isEmpty()){
				TempChannel tempChannel = createTempChannel(event.getGuild(), getClient().getOurUser(), "you got kicked", null, Collections.emptyList(), 0, 0, true);
				movePlayersToChannel(usersToKick, tempChannel.getChannel(), event.getAuthor());
			}	
		} else {
			sendMessage("You can only use this command if you are in a TempChannel that you own", event.getAuthor().getOrCreatePMChannel(), false);
		}
	}

	@CommandHandler({"ban", "b", "deny", "remove"})
	protected void executeBanCommand(MessageReceivedEvent event, Command command) {
		IVoiceChannel channelToBanFrom = event.getAuthor().getVoiceStateForGuild(event.getGuild()).getChannel();
		TempChannel tempChannelToBanFrom = tempChannelsByGuild.get(event.getGuild()).getTempChannelForChannel(channelToBanFrom);
		if(tempChannelToBanFrom != null && event.getAuthor().equals(tempChannelToBanFrom.getOwner())){
			List<IUser> usersToBan = OptionParsing.parseUserList(command.getArguments().orElse(Collections.emptyList()), getClient());
			if(usersToBan.remove(event.getAuthor())){
				sendMessage("You tried to ban yourself from your own TempChannel :scream:", event.getAuthor().getOrCreatePMChannel(), false);
			}
			//filter users who arent in the channel
			usersToBan = usersToBan.stream()
					.filter(T -> T.getVoiceStateForGuild(event.getGuild()).getChannel() != null)
					.filter(T -> T.getVoiceStateForGuild(event.getGuild()).getChannel().equals(channelToBanFrom)).collect(Collectors.toList());
			if(!usersToBan.isEmpty()){
				denyUsersToJoinChannel(usersToBan, event.getAuthor().getVoiceStateForGuild(event.getGuild()).getChannel());	
				TempChannel tempChannel = createTempChannel(event.getGuild(), getClient().getOurUser(), "you got banned", null, Collections.emptyList(), 0, 0, true);
				movePlayersToChannel(usersToBan, tempChannel.getChannel(), event.getAuthor());
				sendMessage("The mentioned user(s) got banned form your TempChannel `" + tempChannelToBanFrom.getChannel().getName() + "`!", event.getChannel(), false);
			}	
		} else {
			sendMessage("You can only use this command if you are in a TempChannel that you own", event.getAuthor().getOrCreatePMChannel(), false);
		}		
	}
	
	@CommandHandler({"unban", "ub", "allow", "add"})
	protected void executeUnBanCommand(MessageReceivedEvent event, Command command) {
		IVoiceChannel channelToUnBanFrom = event.getAuthor().getVoiceStateForGuild(event.getGuild()).getChannel();
		TempChannel tempChannelToUnBanFrom = tempChannelsByGuild.get(event.getGuild()).getTempChannelForChannel(channelToUnBanFrom);
		if(tempChannelToUnBanFrom != null && event.getAuthor().equals(tempChannelToUnBanFrom.getOwner())){
			List<IUser> usersToUnBan = OptionParsing.parseUserList(command.getArguments().orElse(Collections.emptyList()), getClient());
			usersToUnBan.remove(event.getAuthor());

			if(!usersToUnBan.isEmpty()){
				allowUsersToJoinChannel(usersToUnBan, event.getAuthor().getVoiceStateForGuild(event.getGuild()).getChannel());
				sendMessage("The mentioned user(s) may now join your TempChannel `" + tempChannelToUnBanFrom.getChannel().getName() + "`!", event.getChannel(), false);
			}	
		} else {
			sendMessage("You can only use this command if you are in a TempChannel that you own", event.getAuthor().getOrCreatePMChannel(), false);
		}		
	}


	@CommandHandler("help")
	protected void executeHelpCommand(MessageReceivedEvent event, Command command) {
		sendMessage("List of commands: https://www.maxkroner.de/projects/TempChannels/#start", event.getChannel(), false);
	}
	
	@Override
	@CommandHandler("tempChannelsPrefix")
	protected void prefix(MessageReceivedEvent event, Command command) {
		super.prefix(event, command);
	}

	// ----- EXCECUTING METHODS ----- //
	private TempChannel createTempChannel(IGuild guild, IUser owner, String channelName, List<IUser> allowedUsers, List<IUser> movePlayers, int limit,
			int timeout, boolean kickOrBanChannel) {

		// create the new channel
		IVoiceChannel channel = guild.createVoiceChannel(channelName);
		Logger.info("Created channel: {}", channel.getName());

		// put channel to temp category
		ICategory tempCategoryForGuild = getTempCategoryForGuild(guild);
		if(tempCategoryForGuild != null) {
			channel.changeCategory(tempCategoryForGuild);
		}
		

		// set user limit
		channel.changeUserLimit(limit);

		// add temp-Channel
		TempChannel tempChannel = new TempChannel(channel, owner, timeout, kickOrBanChannel);
		tempChannelsByGuild.get(guild).addTempChannel(tempChannel);

		// set channel permissions
		setChannelPermissions(owner, allowedUsers, guild, channel);

		// move players into new channel
		movePlayersToChannel(movePlayers, channel, owner);

		return tempChannel;
	}

	private void movePlayersToChannel(List<IUser> playersToMove, IVoiceChannel channel, IUser author) {
		// only move players who are in the same voice channel as the author
		IChannel authorChannel = author.getVoiceStateForGuild(channel.getGuild()).getChannel();
		for (IUser user : playersToMove) {
			if (user.getVoiceStateForGuild(channel.getGuild()).getChannel() == authorChannel) {
				user.moveToVoiceChannel(channel);
			} else {
				sendPrivateMessage(author, "The user " + user + " wasn't in the same channel as you and therefore couldn't be moved.");
			}
		}
		if (!playersToMove.isEmpty()) {
			Logger.info("Moved players: {}", playersToMove.stream().map(n -> n.getName()).collect(Collectors.joining(", ")));
		}
	}

	private void setChannelPermissions(IUser owner, List<IUser> allowedUsers, IGuild guild, IVoiceChannel channel) {
		// channel owner can do everything
		channel.overrideUserPermissions(owner, EnumSet.allOf(Permissions.class), empty);
		if (allowedUsers != null) {
			// allowedUsers may connect
			for (IUser user : allowedUsers) {
				channel.overrideUserPermissions(user, voice_connect, empty);
			}
			// everyone else may not connect
			channel.overrideRolePermissions(guild.getEveryoneRole(), empty, voice_connect);
		}
	}

	private ICategory getTempCategoryForGuild(IGuild guild) {
		ICategory targetCategory = null;

		List<ICategory> temp_categories = guild.getCategoriesByName("Temporary Channel");
		if (!temp_categories.isEmpty()) {
			targetCategory = temp_categories.get(0);
		} else {
			try{
				ICategory newCategory = guild.createCategory("Temporary Channel");
				targetCategory = newCategory;
			} catch(MissingPermissionsException e) {
				Logger.info("Could not create category because of missing permissions");
				return null;
			} catch(Exception e) {
				Logger.info("Error while creating category");
			}
			
		}

		return targetCategory;
	}
	
	private void allowUsersToJoinChannel(List<IUser> usersToUnBan, IVoiceChannel channel) {
		for (IUser user : usersToUnBan) {
			channel.overrideUserPermissions(user, voice_connect, empty);
		}	
	}
	
	private void denyUsersToJoinChannel(List<IUser> usersToBan, IVoiceChannel channel) {
		for (IUser user : usersToBan) {
			channel.overrideUserPermissions(user, empty, voice_connect);
		}	
	}

	// ----- HELPER METHODS ----- //
	private Collection<TempChannel> getUserChannelsOnGuild(IUser user, IGuild guild) {
		return tempChannelsByGuild.get(guild).getTempChannelListForUser(user);
	}

	private int getUserChannelCountOnGuild(IUser user, IGuild guild) {
		return tempChannelsByGuild.get(guild).getUserChannelCount(user);
	}

	/**
	 * saves all current TempChannels to a file
	 */
	public void saveTempChannel() {
		// create an ArrayList with Transfer Objects (TOs) for each TempChannel
		ArrayList<TempChannelTO> tempChannelTOs = new ArrayList<>();

		// for each tempChannel add a TempChannel TransferObject to the tempChannelTos ArrayList
		tempChannelsByGuild.values().stream()
				.forEach(T -> T.getAllTempChannel().stream().map(TempChannelTO::createFromTempChannel).forEachOrdered(tempChannelTOs::add));

		// save the ArrayList to a file
		writeObjectToFile(tempChannelTOs, path_serialized_tempChannels, file_name);
		// TODO find out how to log after shutdown-hook was called
		// Logger.info("{} serialized TempChannels are saved.", tempChannelTOs.size());
	}

	private void writeObjectToFile(Object object, String path, String fileName) {
		try {
			String filePath = Paths.get(path, fileName).toString();
			File file = new File(path);
			if (!file.exists()) {
				file.mkdirs();
			}
			FileOutputStream fileOut = new FileOutputStream(filePath);
			ObjectOutputStream out = new ObjectOutputStream(fileOut);
			out.writeObject(object);
			out.close();
			fileOut.close();
			// TODO fix
			// Logger.info("Serialized objects written to: \"{}\"", filePath);
		} catch (IOException i) {
			Logger.error(i);
		}
	}

	@SuppressWarnings("unchecked")
	private void readTempChannelsFromFile() {
		String pathToFile = Paths.get(path_serialized_tempChannels, file_name).toString();
		try {
			if (new File(pathToFile).exists()) {
				// read ArrayList of TempChannelTOs from file
				ArrayList<TempChannelTO> tempChannelTOs = (ArrayList<TempChannelTO>) readObjectFromFile(pathToFile);
				Logger.info("Read {} serialized TempChannels", tempChannelTOs.size());
				int importedCount = 0;
				// create TempChannels from TOs
				for (TempChannelTO to : tempChannelTOs) {
					IVoiceChannel voiceChannel = getClient().getVoiceChannelByID(to.getChannelSnowflakeID());
					if ((voiceChannel != null) && !voiceChannel.isDeleted()) {// if channel still exists
						IGuild guild = voiceChannel.getGuild();
						IUser user = guild.getUserByID(to.getOwnerSnowflakeID());
						if ((user != null) // if owner is still in guild
								&& tempChannelsByGuild.containsKey(guild)) { // and bot is still connected to guild
							TempChannel tempChannel = new TempChannel(voiceChannel, user, to.getTimeoutInMinutes(), to.getEmptyMinutes());
							tempChannelsByGuild.get(guild).addTempChannel(tempChannel);
							importedCount++;
						}
					}

				}
				Logger.info("Importet {} from the {} serialized TempChannels", importedCount, tempChannelTOs.size());

			} else {
				Logger.info("No serialized TempChannels found.");
			}

		} catch (Exception e) {
			Logger.error(e);
			return;
		}

	}

	private Object readObjectFromFile(String file) throws FileNotFoundException, IOException, ClassNotFoundException {
		FileInputStream fileIn = new FileInputStream(file);
		ObjectInputStream in = new ObjectInputStream(fileIn);
		Object object = in.readObject();
		in.close();
		fileIn.close();
		return object;
	}

	private void removeUnknownChannelsForGuild(IGuild guild) {
		boolean textChannelInTempCategory = false;
		ICategory tempCategory = getTempCategoryForGuild(guild);
		if (tempCategory != null) {
			for (IVoiceChannel channel : tempCategory.getVoiceChannels()) {
				if (!tempChannelsByGuild.get(guild).isTempChannelForChannelExistentInMap(channel)) {
					TempChannel tempChannel = new TempChannel(channel, getClient().getOurUser(), timeout_for_unknown_channels, false);
					tempChannelsByGuild.get(guild).addTempChannel(tempChannel);
					Logger.info("Created 5min timeout TempChannel for unkown channel: {} in guild {}", channel.getName(), guild.getName());
				}
			}
			for (IChannel channel : tempCategory.getChannels()) {
				textChannelInTempCategory = true;
				try {
					channel.delete();
					Logger.info("Deleted text channel {} in TempChannel category", channel.getName());
				} catch(MissingPermissionsException e) {
					Logger.info("Could not delte channel because of missing permissions");
				}
			}
			if (textChannelInTempCategory) {
				sendMessage(
						"Who the hell created a text channel in the TempChannel category? Get your life fixed! I had to clean up this mess for you...",
						guild.getDefaultChannel(), false);
			}
		}
	}

	private void removeUnkownChannelsForGuild(Set<IGuild> guilds) {
		// get TempCategory for each guild and remove VoiceChannels in the category which aren't in the TempChannelMap
		for (IGuild guild : guilds) {
			removeUnknownChannelsForGuild(guild);
		}

	}

	/**
	 * saves TempChannels in stash to reuse them incase the guild gets available again
	 * 
	 * @param guild
	 *            guild for which the tempChannels should be stashed
	 */
	private void stashChannelsAndRemoveMap(IGuild guild) {
		Logger.info("Stashing {} tempChannels from guild {}.", tempChannelsByGuild.get(guild).getAllTempChannel().size(), guild.getName());
		if(tempChannelsByGuild.get(guild) != null) {
			stashedChannels.addAll(tempChannelsByGuild.get(guild).getAllTempChannel());
		}	
		tempChannelsByGuild.remove(guild);
	}

	/**
	 * imports the stashed tempChannels from the specified guild
	 * 
	 * @param guild
	 *            the guild for which the stashed tempChannels should be imported
	 */
	private void importStashedChannelsForGuild(IGuild guild) {
		List<IUser> users = guild.getUsers();
		List<IVoiceChannel> channels = guild.getVoiceChannels();
		Iterator<TempChannel> iterator = stashedChannels.parallelStream() // only import TempChannels
				.filter(T -> T.getChannel().getGuild().equals(guild)) // if the guild is correct
				.filter(T -> channels.contains(T.getChannel())) // if the channel still exists
				.filter(T -> users.contains(T.getOwner())) // if the owner is still connected to the server
				.iterator();
		int importedCount = 0;
		while (iterator.hasNext()) {
			tempChannelsByGuild.get(guild).addTempChannel(iterator.next());
			importedCount++;
		}

		Logger.info("Imported {} stashed channels for guild {}", importedCount, guild.getName());
	}

	private void setEmptyMinutesToZero(IVoiceChannel voiceChannel) {
		TempChannelMap tempChannelMap = tempChannelsByGuild.get(voiceChannel.getGuild());
		if (tempChannelMap != null) {
			TempChannel tempChannel = tempChannelMap.getTempChannelForChannel(voiceChannel);
			if (tempChannel != null) {
				tempChannel.setEmptyMinutes(0);
				Logger.info("User joined tempChannel {}, setting empty minutes to 0", voiceChannel.getName());
			}
		}
	}
	
	private void deleteIfKickOrBanChannel(IVoiceChannel voiceChannel) {
		TempChannelMap tempChannelMap = tempChannelsByGuild.get(voiceChannel.getGuild());
		if (tempChannelMap != null) {
			TempChannel tempChannel = tempChannelMap.getTempChannelForChannel(voiceChannel);
			if (tempChannel != null && tempChannel.isKickOrBanChannel() && tempChannel.getChannel().getConnectedUsers().isEmpty()) {
				tempChannel.getChannel().delete();
			}
		}
	}

	private void sendErrorMessages(IChannel channel, IUser author, List<String> errorMessages, String originalMessage) {
		if (errorMessages.size() == 1) {
			sendMessage("Ey " + author + ": " + errorMessages.get(0), channel, false);
		} else {
			sendMessage("Ey " + author + ", there was something wrong with your channel command. Look in private chat for further information.",
					channel, false);
			MessageBuilder mb = new MessageBuilder(getClient()).withChannel(author.getOrCreatePMChannel());
			mb.appendContent("There were some things wrong with your channel command: \"" + originalMessage + "\"\n");
			int count = 1;
			for (String errMessage : errorMessages) {
				mb.appendContent("\t" + count + ". " + errMessage);
				mb.appendContent("\n");
				count++;
			}
			mb.build();
		}
	}

	private boolean checkIfPrequisitesAreMet(IChannel channel, IUser author, IGuild guild, List<String> errorMessages) {
		// check if User is in voice channel of the guild
		if (author.getVoiceStateForGuild(guild).getChannel() == null) {
			errorMessages.add("Please join a voice channel before activating a channel command.");
			Logger.info("Received channel command, but user wasn't in a voiceChannel");
			return false;
		}

		// check if User-Channel-Count-Limit is reached
		if (getUserChannelCountOnGuild(author, guild) >= USER_CHANNEL_LIMIT) {
			errorMessages.add(
					"You reached the personal channel limit of " + USER_CHANNEL_LIMIT + ". Use !cc to delete all of your empty temporary channels. "
							+ "With the option -f you can force to delete all channels, even those who aren't empty!");
			Logger.info("Received channel command, but user has already reached his channel limit");
			return false;
		}

		return true;
	}
	
	 private String createChannelName(IUser user, IGuild guild) {
	        ArrayList<TempChannel> userChannels = tempChannelsByGuild.get(guild).getTempChannelListForUser(user);
	        if (userChannels.size() == 0) {
	            return String.valueOf(user.getName()) + " #1";
	        }
	        return this.findFreeChannelNameForUser(user, userChannels);
	    }
	
    private String findFreeChannelNameForUser(IUser user, ArrayList<TempChannel> userChannels) {
        int i = 1;
        while (i <= USER_CHANNEL_LIMIT) {
            String channelname = String.valueOf(user.getName()) + " #" + i;
            boolean channelexists = false;
            for (TempChannel userChannel : userChannels) {
                if (!userChannel.getChannel().getName().equals(channelname)) continue;
                channelexists = true;
                break;
            }
            if (!channelexists) {
                return channelname;
            }
            ++i;
        }
        return user.getName();
    }

}
