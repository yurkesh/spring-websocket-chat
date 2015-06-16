package com.tempest.moonlight.server.services;

import com.tempest.moonlight.server.domain.ChatMessage;

import java.util.Collection;

/**
 * Created by Yurii on 2015-05-08.
 */
public interface MessageService {
    void saveMessage(ChatMessage chatMessage);
    Collection<ChatMessage> getMessagesOfUser(String user);
    Collection<ChatMessage> getMessagesOfGroup(String group);
    Collection<ChatMessage> getMessagesOfChannel(String channel);
    void markMessageDelivered(ChatMessage chatMessage, boolean delivered);
}
