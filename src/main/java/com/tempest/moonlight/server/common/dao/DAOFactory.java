package com.tempest.moonlight.server.common.dao;

import com.tempest.moonlight.server.domain.messages.ChatMessage;
import com.tempest.moonlight.server.domain.MessageKey;
import com.tempest.moonlight.server.domain.User;

/**
 * Created by Yurii on 4/21/2015.
 */
public interface DAOFactory {

    DAO<User, String> getUserDAO();

    DAO<ChatMessage, MessageKey> getMessageDAO();

}
