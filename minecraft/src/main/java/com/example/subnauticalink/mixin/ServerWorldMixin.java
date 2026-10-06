package com.example.subnauticalink.mixin;

/**
 * No longer used. Cracks on a block being broken are now passed on by each player's own game
 * (see ClientWorldMixin), not by the server. This file is kept only because files can't be
 * removed from the project folder from here; it does nothing.
 */
public final class ServerWorldMixin {
	private ServerWorldMixin() {
	}
}
