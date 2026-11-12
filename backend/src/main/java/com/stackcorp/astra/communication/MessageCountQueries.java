package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.api.MessageCounts;

import org.springframework.stereotype.Service;

@Service
class MessageCountQueries implements MessageCounts {

	private final MessageRepository messages;

	MessageCountQueries(MessageRepository messages) {
		this.messages = messages;
	}

	@Override
	public int unread(long userId) {
		return this.messages.unreadCount(userId);
	}

}
