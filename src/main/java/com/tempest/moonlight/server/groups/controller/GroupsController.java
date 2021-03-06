package com.tempest.moonlight.server.groups.controller;

import com.tempest.moonlight.server.contacts.controller.ContactsController;
import com.tempest.moonlight.server.domain.Group;
import com.tempest.moonlight.server.domain.ParticipantType;
import com.tempest.moonlight.server.domain.contacts.ContactRequest;
import com.tempest.moonlight.server.domain.contacts.GenericParticipant;
import com.tempest.moonlight.server.contacts.exceptions.ContactsException;
import com.tempest.moonlight.server.groups.exceptions.*;
import com.tempest.moonlight.server.common.dto.DtoConverter;
import com.tempest.moonlight.server.contacts.dto.ContactRequestDTO;
import com.tempest.moonlight.server.contacts.dto.GenericParticipantDTO;
import com.tempest.moonlight.server.groups.dto.GroupParticipantsChangeDTO;
import com.tempest.moonlight.server.groups.dto.GroupParticipantsDTO;
import com.tempest.moonlight.server.groups.service.GroupParticipantsChangesHolder;
import com.tempest.moonlight.server.groups.service.GroupService;
import com.tempest.moonlight.server.util.StringUtils;
import com.tempest.moonlight.server.websockets.ToParticipantSender;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by Yurii on 2015-06-22.
 */
@Controller
public class GroupsController {

    private static final Logger logger = Logger.getLogger(GroupsController.class.getName());

    @Autowired
    private GroupService groupService;

    @Autowired
    private DtoConverter dtoConverter;

    @Autowired
    private ToParticipantSender toParticipantSender;

    @Autowired
    private ContactsController contactsController;

    @MessageMapping("groups/create")
    @SendToUser("/queue/groups")
    public GroupParticipantsDTO onCreateGroup(Principal principal, @Payload GenericParticipantDTO genericParticipantDTO) throws GroupsException {
        String groupSignature = genericParticipantDTO.getSignature();
        if(StringUtils.isEmpty(groupSignature)) {
            throw new InvalidGroupSignatureException();
        }
        Group group = checkGetGroup(groupSignature, false);
        groupService.addUserToGroup(group, principal.getName());
        return new GroupParticipantsDTO(
                group,
                (List<GenericParticipantDTO>) dtoConverter.convertToDTOs(groupService.getParticipants(group))
        );
    }

    @MessageMapping("groups/changes")
    @SendToUser("/queue/groups")
    public GroupParticipantsDTO onGroupParticipantsChange(Principal principal, @Payload GroupParticipantsChangeDTO participantsChangeDTO) throws GroupsException {
        String groupSignature = participantsChangeDTO.getSignature();
        if(StringUtils.isEmpty(groupSignature)) {
            throw new InvalidGroupSignatureException();
        }

        logger.info("onGroupParticipantsChange: participantsChangeDTO = " + participantsChangeDTO);

        Group group = checkGetGroup(groupSignature, true);
        if(!groupService.checkUserBelongsToGroup(group, principal.getName())) {
            throw new IllegalGroupAccessException(groupSignature);
        }

        GroupParticipantsChangesHolder changesHolder = groupService.processAddRemoveParticipants(
                group,
                dtoConverter.convertFromDTOs(participantsChangeDTO.getAdd()),
                dtoConverter.convertFromDTOs(participantsChangeDTO.getRemove())
        );

        logger.info("onGroupParticipantsChange: changesHolder = " + changesHolder);

        Collection<GenericParticipant> updatedParticipants = changesHolder.updatedParticipants;



        String sender = principal.getName();
        long millis = System.currentTimeMillis();

        Map<GenericParticipant, ContactRequestDTO> groupAddNotifications = changesHolder.addedParticipants.stream().collect(
                Collectors.toMap(
                        Function.identity(),
                        addedParticipant ->
                                new ContactRequestDTO(
                                        sender,
                                        addedParticipant.getSignature(),
                                        ParticipantType.GROUP,
                                        groupSignature,
                                        ContactRequest.Status.PENDING,
                                        millis
                                )
                )
        );

        Map<GenericParticipant, ContactRequestDTO> groupRemoveNotifications = changesHolder.removedParticipants.stream().collect(
                Collectors.toMap(
                        Function.identity(),
                        removedParticipant ->
                                new ContactRequestDTO(
                                        sender,
                                        removedParticipant.getSignature(),
                                        ParticipantType.GROUP,
                                        groupSignature,
                                        ContactRequest.Status.REJECTED,
                                        millis
                                )
                )
        );

        groupAddNotifications.forEach(
                (participant, contactRequestDTO) -> {
                    try {
                        contactsController.onContactRequest(
                                principal, contactRequestDTO
                        );
                    } catch (ContactsException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        groupRemoveNotifications.forEach(
                (participant, contactRequestDTO) -> {
                    try {
                        contactsController.onContactRequest(
                                principal, contactRequestDTO
                        );
                    } catch (ContactsException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

//        String destination = "contacts/reply";
//        toParticipantSender.sendToUsersQueue(
//                groupRemoveNotifications,
//                destination
//        );
//
//        toParticipantSender.sendToUsersQueue(
//                groupAddNotifications,
//                destination
//        );

        return new GroupParticipantsDTO(
                group,
                (List<GenericParticipantDTO>) dtoConverter.convertToDTOs(updatedParticipants)
        );
    }

    @MessageMapping("groups/get")
    @SendToUser("queue/groups")
    public GroupParticipantsDTO onGetGroupParticipants(Principal principal, GenericParticipantDTO genericParticipantDTO) throws GroupsException {
        String groupSignature = genericParticipantDTO.getSignature();
        if(StringUtils.isEmpty(groupSignature)) {
            throw new InvalidGroupSignatureException();
        }
        String principalName = principal.getName();

        Group group = checkGetGroup(groupSignature, true);
        if(!group.isOpened()) {
            if(!groupService.checkUserBelongsToGroup(group, principalName)) {
                throw new IllegalGroupAccessException(groupSignature);
            }
        }

        return new GroupParticipantsDTO(
                groupSignature,
                (List<GenericParticipantDTO>) dtoConverter.convertToDTOs(groupService.getParticipants(groupSignature))
        );
    }

    private Group checkGetGroup(String groupSignature, boolean requireExists) throws GroupsException {
        if(groupService.existsGroup(groupSignature) ^ requireExists) {
            if(requireExists) {
                throw new GroupNotExistsException(groupSignature);
            } else {
                throw new GroupAlreadyExistsException(groupSignature);
            }
        } else {
            if(requireExists) {
                return groupService.getGroup(groupSignature);
            } else {
                return groupService.createGroup(groupSignature);
            }
        }
    }

    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public String onGroupsException(GroupsException e) {
        return e.getMessage();
    }

}
