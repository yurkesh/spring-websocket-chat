package com.tempest.moonlight.server.messages.controller;

import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.tempest.moonlight.server.messages.exceptions.*;
import com.tempest.moonlight.server.domain.ParticipantType;
import com.tempest.moonlight.server.domain.contacts.GenericParticipant;
import com.tempest.moonlight.server.domain.messages.MessageDeliveryStatus;
import com.tempest.moonlight.server.domain.messages.MessageStatus;
import com.tempest.moonlight.server.users.dao.ActiveUsersDAO;
import com.tempest.moonlight.server.groups.service.GroupService;
import com.tempest.moonlight.server.messages.services.MessageService;
import com.tempest.moonlight.server.users.service.UserService;
import com.tempest.moonlight.server.common.dto.DtoConverter;
import com.tempest.moonlight.server.messages.dto.ChatMessageDTO;
import com.tempest.moonlight.server.util.StringUtils;
import com.tempest.moonlight.server.websockets.CustomMessageHeadersAccessor;
import com.tempest.moonlight.server.websockets.ToParticipantSender;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;

import com.tempest.moonlight.server.domain.messages.ChatMessage;
//import com.tempest.moonlight.server.domain.SessionProfanity;
//import com.tempest.moonlight.server.util.ProfanityChecker;

@Controller
public class ChatController {
	private static final Logger logger = Logger.getLogger(ChatController.class.getName());

	@Autowired
    private WebSocketMessageBrokerStats stats;

	@Autowired
    private ActiveUsersDAO activeUsersDAO;

    @Autowired
    private ToParticipantSender toParticipantSender;

    @Autowired
    private DtoConverter dtoConverter;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupService groupService;

    /*
	@SubscribeMapping("/chat.participants")
	public Collection<String> retrieveParticipants(Principal principal) {
        Collection<String> allActiveUsers = activeUsersDAO.getActiveUsers();
        allActiveUsers.remove(principal.getName());
        return allActiveUsers;
    }
    */

    @MessageMapping("/chat/private")
    public void onPrivateMessage(Principal sender, Message message, @Payload ChatMessageDTO chatMessageDTO) throws MessageHandlingException {
        processPrivateMessage(sender, message, chatMessageDTO);
    }

    @MessageMapping("/chat/private/{login}")
    public void onPrivateMessage(Principal sender, Message message, @Payload ChatMessageDTO chatMessageDTO, @DestinationVariable("login") String login) throws MessageHandlingException {
        chatMessageDTO.setRecipient(login);
        processPrivateMessage(sender, message, chatMessageDTO);
    }

    private void processPrivateMessage(Principal sender, Message message, ChatMessageDTO chatMessageDTO) throws MessageHandlingException {
        ChatMessage chatMessage = dtoConverter.convertFromDTO(chatMessageDTO, ChatMessage.class);
        chatMessage.setFrom(sender.getName());

        chatMessage = onUserMessageInner(chatMessage, message, ParticipantType.USER);

        sendDeliveryStatus(chatMessage.getFrom(), new MessageDeliveryStatus(chatMessage, MessageStatus.ARRIVED));
        toParticipantSender.sendToUserQueue(
                chatMessage.getRecipient().getSignature(),
                "chat/incoming",
                dtoConverter.convertToDTO(chatMessage, ChatMessageDTO.class)
        );
    }

    private ChatMessage onUserMessageInner(ChatMessage chatMessage, Message message, ParticipantType recipientType) throws MessageHandlingException {
        GenericParticipant recipient = checkSetUpMessage(chatMessage, message, recipientType).getRecipient();

        String recipientSignature = recipient.getSignature();
        if(recipientType == ParticipantType.USER) {
            if (!userService.checkUserExists(recipientSignature)) {
                throw new RecipientDoesNotExistException(ParticipantType.USER, recipientSignature);
            }
        } else if(recipientType == ParticipantType.GROUP) {
            if(!groupService.existsGroup(recipientSignature)) {
                throw new RecipientDoesNotExistException(ParticipantType.GROUP, recipientSignature);
            }
            if(!groupService.checkUserBelongsToGroup(recipientSignature, chatMessage.getFrom())) {
                throw new IllegalGroupRecipientException(recipientSignature);
            }
        }

        logger.info("user sent message = " + chatMessage);

        messageService.saveMessage(chatMessage);
        return chatMessage;
    }

    public static ChatMessage checkSetUpMessage(ChatMessage chatMessage, Message message, ParticipantType type) throws MessageHandlingException {
        if(StringUtils.isEmpty(chatMessage.getPacketId())) {
            throw new EmptyMessagePacketIdException();
        }

        GenericParticipant recipient = chatMessage.getRecipient();
        if(recipient.getType() == null) {
            chatMessage.setType(type);
        } else if(recipient.getType() != type) {
            throw new IllegalMessageRecipientType(recipient.getType());
        }

        String recipientSignature = recipient.getSignature();
        if(!StringUtils.hasText(recipientSignature)) {
            throw new InvalidUserLoginException(recipientSignature);
        }

        CustomMessageHeadersAccessor headerAccessor = CustomMessageHeadersAccessor.wrap(message);
        headerAccessor.setImmutable();

        chatMessage.setTime(headerAccessor.getTimestamp());
        chatMessage.setUuid(headerAccessor.getId().toString());

        return chatMessage;
    }

    @MessageMapping("messages/delivery")
    public void onMessageDeliveryStatus(Principal principal, MessageDeliveryStatus deliveryStatus) throws MessageHandlingException {
        deliveryStatus.setTo(principal.getName());
        messageService.updateMessageDeliveryStatus(deliveryStatus);

        sendDeliveryStatus(deliveryStatus.getFrom(), deliveryStatus);
    }

    private void sendDeliveryStatus(String to, MessageDeliveryStatus deliveryStatus) {
        toParticipantSender.sendToUserQueue(to, "messages/delivery", deliveryStatus);
    }

    @MessageMapping("/chat/group")
    public void onGroupMessage(Principal sender, Message message, @Payload ChatMessageDTO chatMessageDTO) throws MessageHandlingException {
        processGroupMessage(sender, message, chatMessageDTO);
    }

    public void processGroupMessage(Principal sender, Message message, ChatMessageDTO chatMessageDTO) throws MessageHandlingException {
        ChatMessage chatMessage = dtoConverter.convertFromDTO(chatMessageDTO);
        chatMessage.setFrom(sender.getName());

        chatMessage = onUserMessageInner(chatMessage, message, ParticipantType.GROUP);

        sendDeliveryStatus(chatMessage.getFrom(), new MessageDeliveryStatus(chatMessage, MessageStatus.ARRIVED));

        ChatMessageDTO groupMessageDTO = (ChatMessageDTO) dtoConverter.convertToDTO(chatMessage, ChatMessageDTO.class);
        Collection<GenericParticipant> participants = groupService.getParticipants(chatMessage.getRecipient().getSignature());
        Map<GenericParticipant, ChatMessageDTO> participantsMessagesMap = participants.stream().collect(
                Collectors.toMap(
                        Function.identity(),
                        participant -> groupMessageDTO
                )
        );
        participantsMessagesMap.remove(new GenericParticipant(ParticipantType.USER, sender.getName()));
        toParticipantSender.sendToUsersQueue(
                participantsMessagesMap,
                "chat/incoming"
        );
    }

//    @MessageMapping("/chat.message")
//    public ChatMessage onUserMessage(Message message, @Payload ChatMessage chatMessage, Principal principal) {
//        messageService.saveMessage(setUpMessage(chatMessage, principal, null, ParticipantType.USER, message));
//        return chatMessage;
//    }

//	@MessageMapping("/chat.private.{login}")
//	public void onUserPrivateMessage(Message message, @Payload ChatMessage chatMessage, @DestinationVariable("login") String login, Principal principal) throws MessageHandlingException {
//        if(!StringUtils.hasText(login)) {
//            throw new InvalidUserLoginException(login);
//        }
//
//        if(!userService.checkUserExists(login)) {
//            throw new RecipientDoesNotExistException(login);
//        }
//
//        messageService.saveMessage(setUpMessage(chatMessage, principal, login, ParticipantType.USER, message));
//
////		simpMessagingTemplate.convertAndSend("/user/" + login + "/queue/chat.message", chatMessage);
//        toParticipantSender.sendToUserQueue(login, "chat.message", chatMessage);
//	}
	
	@MessageExceptionHandler
	@SendToUser(value = "/queue/errors", broadcast = false)
	public String onMessageHandlingException(MessageHandlingException e) {
		return e.getMessage();
	}

//    @MessageExceptionHandler
//    @SendToUser(value = "/queue/errors", broadcast = false)
//    public String handleEx(InvalidUserLoginException e) {
//        return e.getMessage();
//    }
	
	@RequestMapping("/stats")
	public @ResponseBody WebSocketMessageBrokerStats showStats() {
		return stats;
	}

}