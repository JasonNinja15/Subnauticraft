package com.example.subnauticalink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The connection to Subnautica.
 *
 * <p>Minecraft opens a "port" on this computer only (nothing outside the PC can reach it) and
 * waits. The Subnautica mod connects to it. After that, each side sends the other short lines
 * of text such as {@code DAMAGE 0.25} or {@code DEATH}.
 *
 * <p>Waiting for a connection and waiting for the next line both block, so they happen on a
 * separate background thread. That thread never touches the game directly: it only drops
 * lines into a queue, and the game picks them up on its own thread once per tick. (The one
 * exception is collision answers, which the game is actively waiting for.)
 */
public final class LinkConnection {
	/** Special queue entries for "Subnautica connected" and "Subnautica went away". */
	public static final String CONNECTED = "#CONNECTED";
	public static final String DISCONNECTED = "#DISCONNECTED";

	private final int port;
	private final Queue<String> incoming = new ConcurrentLinkedQueue<>();

	/** Who to hand "RAYHIT" answers to. Set by the player's own game. */
	public volatile Consumer<String> rayAnswers;

	private volatile boolean running;
	private volatile ServerSocket listener;
	private volatile Socket socket;
	private volatile PrintWriter writer;

	public LinkConnection(int port) {
		this.port = port;
	}

	/** Starts waiting for Subnautica in the background. */
	public void start() {
		this.running = true;
		Thread thread = new Thread(this::listenLoop, "Subnautica Link");
		thread.setDaemon(true);
		thread.start();
	}

	/** Closes everything. Called when the world is closed. */
	public void stop() {
		this.running = false;
		closeQuietly(this.socket);
		closeQuietly(this.listener);
	}

	/** The next line received from Subnautica, or null if there is none waiting. */
	public String poll() {
		return this.incoming.poll();
	}

	/** True while Subnautica is connected. */
	public boolean isConnected() {
		return this.writer != null;
	}

	/** Sends one line to Subnautica. Does nothing if Subnautica isn't connected. */
	public synchronized void send(String line) {
		PrintWriter out = this.writer;

		if (out != null) {
			out.println(line);
		}
	}

	private void listenLoop() {
		try (ServerSocket server = new ServerSocket(this.port, 1, InetAddress.getLoopbackAddress())) {
			this.listener = server;

			while (this.running) {
				// Waits here until Subnautica connects.
				try (Socket client = server.accept()) {
					this.socket = client;
					// Send every line at once instead of saving small ones up: the collision
					// questions need their answers within a few milliseconds.
					client.setTcpNoDelay(true);
					this.writer = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
					this.incoming.add(CONNECTED);

					BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
					String line;

					// Waits here for each line; ends when Subnautica closes or crashes.
					while (this.running && (line = reader.readLine()) != null) {
						if (line.startsWith("SWEPT ")) {
							// A collision answer. The game is waiting on it right now, so it
							// is handed over directly instead of queueing for the next tick.
							RemoteCollision.onAnswer(line.substring("SWEPT ".length()));
						} else if (line.startsWith("RAYHIT ")) {
							// What flying projectiles are about to hit. Passed straight on
							// too, so the server hears of a hit as soon as possible.
							Consumer<String> listener = this.rayAnswers;

							if (listener != null) {
								listener.accept(line.substring("RAYHIT ".length()));
							}
						} else if (line.startsWith("POINTER ")) {
							// The mouse pointer over an open Minecraft screen. Also handed
							// straight over, so the pointer moves smoothly rather than 20
							// times a second.
							RemoteControls.onPointerLine(line.substring("POINTER ".length()));
						} else if (line.startsWith("CLICK ") || line.startsWith("WHEEL ")) {
							// A click waits for the game's next tick, and the pointer may have
							// moved on by then. So where the pointer is at this moment goes with it.
							this.incoming.add(line + String.format(java.util.Locale.ROOT, " @ %.5f %.5f", RemoteControls.pointerX, RemoteControls.pointerY));
						} else {
							this.incoming.add(line);
						}
					}
				} catch (IOException e) {
					if (this.running) {
						SubnauticaLink.LOGGER.info("Subnautica connection ended: {}", e.getMessage());
					}
				} finally {
					this.writer = null;
					this.socket = null;

					if (this.running) {
						this.incoming.add(DISCONNECTED);
					}
				}
			}
		} catch (IOException e) {
			if (this.running) {
				SubnauticaLink.LOGGER.error("Could not open port {} for the Subnautica link", this.port, e);
			}
		}
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ignored) {
				// Already closed.
			}
		}
	}
}
