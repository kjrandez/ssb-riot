package riot;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * The server side class that runs an actual game of SSB
 * This handles all connections, steps and manages all game objects, and takes control
 * of various other game aspects such as collisions, rounds, spawning, and deaths.
 */
public class GameEngine {
	Communicator communicator;
	SpriteManager spriteManager;
	MapManager mapManager;
	
	HashMap<Socket, Character> players;
	ArrayList<GameObject> worldObjects;
	ArrayList<GameObject> overlayObjects;
	ArrayList<Damager> damagers;
	
	public GameEngine(SpriteManager spriteManager, MapManager mapManager) {
		this.spriteManager = spriteManager;
		this.mapManager = mapManager;
		
		communicator = new Communicator(true);
		communicator.acceptIncoming();
		
		players = new HashMap<Socket, Character>();
		worldObjects = new ArrayList<GameObject>();
		overlayObjects = new ArrayList<GameObject>();
		damagers = new ArrayList<Damager>();
		
		worldObjects.add(new Map(this, spriteManager, mapManager, "testmap"));
	}
	
	/**
	 * Returns a reference to the rounds map (assumed to be first element)
	 */
	public Map getMap() {
		return (Map)worldObjects.get(0);
	}
	
	/**
	 * Parses messages coming from clients and redirects them to those clients' characters
	 */
	private void handleMessage(Message message) {
		try {
			Character referral = players.get(message.sender);
			ByteArrayInputStream stream = new ByteArrayInputStream(message.data);
			DataInputStream reader = new DataInputStream(stream);
			switch(reader.readByte()) {
				case Riot.Connect:
					SpawnPlatform platform = new SpawnPlatform(this, spriteManager);
					Character character = new Jigglypuff(this, spriteManager, platform);
					platform.setCharacter(character);
					players.put(message.sender, character);
					worldObjects.add(character);
					worldObjects.add(platform);
					System.out.println("New player joined the game.");
					break;
				case Riot.Disconnect:
					players.remove(message.sender);
					worldObjects.remove(referral);
					System.out.println("Player left the game.");
					break;
				case Riot.Direction:
					int degrees = reader.readInt();
					referral.move(degrees);
					break;
				case Riot.Attack:
					referral.attack();
					break;
				case Riot.Dodge:
					referral.dodge();
					break;
				case Riot.Jump:
					referral.jump();
					break;
				case Riot.Special:
					referral.special();
					break;
				case Riot.Shield:
					referral.shield();
					break;
			}
		}
		catch(IOException ex) {
			System.out.println("Couldn't get client's message.");
		}
	}
	
	/**
	 * Corrects a character who has collided into a map platform by moving it to the edge.
	 */
	public void rectifyPlatformCollision(NaturalObject object, Rectangle platform) {
		Rectangle characterBounds = object.getBoundingBoxes().get(0);
		if(characterBounds.overlaps(platform)) {
			/* Check if we hit the left side. */
			if(characterBounds.minX() < platform.minX() && characterBounds.minY() > platform.minY())
				while(characterBounds.overlaps(platform))
					characterBounds.x = characterBounds.x - 1.0;
			/* Check if we hit the right side. */
			else if(characterBounds.maxX() > platform.maxX() && characterBounds.minY() > platform.minY())
				while(characterBounds.overlaps(platform))
					characterBounds.x = characterBounds.x + 1.0;
			/* Check if we hit the top or bottom. */
			else
				while(characterBounds.overlaps(platform))
					characterBounds.y = characterBounds.y - 1.0;
			object.setLocation(new Point(characterBounds.x+(characterBounds.width/2), characterBounds.maxY()));
		}
	}
	
	/**
	 * Detects whether the character is standing on the platform by checking if its one pixel above
	 */
	public boolean standingOnPlatform(NaturalObject object, Rectangle platform) {
		Rectangle characterBounds = object.getBoundingBoxes().get(0);
		if(characterBounds.maxY() + 1 == platform.minY() && characterBounds.maxX() >= platform.minX() && characterBounds.minX() <= platform.maxX())
			return true;
		else
			return false;
	}
	
	/**
	 * Puts a previously created object into the world
	 */
	public void spawnWorldObject(GameObject object) {
		worldObjects.add(object);
		if(object instanceof Damager) {
			damagers.add((Damager)object);
		}
	}
	
	/**
	 * Removes an object from the world
	 */
	public void removeWorldObject(GameObject object) {
		worldObjects.remove(object);
		if(object instanceof Damager) {
			damagers.remove((Damager)object);
		}
	}
	
	/**
	 * Handles networking aspects (input / output)
	 */
	public void updateNetwork(ArrayList<Rectangle> debugTangles) {
		Message message = communicator.receiveData();
		handleMessage(message);
		Scene scene = new Scene("Test Server", worldObjects, overlayObjects, debugTangles);
		communicator.sendData(scene.serialize());
	}
	
	/**
	 * The major logic loop in the program which iterates at a frame rate of 30 fps.
	 * This steps all of the objects, checking for collisions and major events, and sending
	 * each frame to all of the clients to be drawn by their DummyTerminals.
	 */
	public void gameLoop() {
		
		// THIS FUNCTION IS REALLY BOTHERING ME. There is too much class intimacy and a
		// nasty huge instanceof conditional. SOMEONE THINK OF A STRICTLY POLYMORPHIC SOLUTION
		while(true) {
			long start = System.currentTimeMillis();
			
			/* Step all objects handling collisions and round events. */
			ArrayList<Rectangle> debugTangles = new ArrayList<Rectangle>();
			Map map = (Map)worldObjects.get(0);
			for(GameObject object: worldObjects) {
				object.step();
				if(object instanceof Character) {
					boolean onPlatform = false;
					for(Rectangle platform: map.getBoundingBoxes()) {
						rectifyPlatformCollision((NaturalObject)object, platform);
						if(standingOnPlatform((NaturalObject)object, platform)) {
							onPlatform = true;
							break;
						}
					}
					for(Damager damager: damagers) {
						if(object.getBoundingBoxes().get(0).overlaps(damager.getBoundingBoxes().get(0))) {
							damager.wasUsed();
							((Character)object).damage(damager);
						}
					}
					((Character)object).aerial(!onPlatform);
				}
				for(Rectangle rect: object.getBoundingBoxes()) {
					debugTangles.add(rect);
				}
			}
			updateNetwork(debugTangles);
			
			/* Pause until the next frame. */
			try {Thread.sleep(30 - System.currentTimeMillis() + start);}
			catch(Exception ex){}
		}
	}
}
