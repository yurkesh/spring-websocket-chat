package com.tempest.moonlight.server.domain;

import com.tempest.moonlight.server.repository.persistance.dao.IdentifiedEntity;

import java.io.Serializable;

/**
 * Created by Yurii on 4/21/2015.
 */
public class ChatMessage implements Serializable, IdentifiedEntity<MessageKey> {

    private transient MessageKey key;

    private String from;
    private String to;

    private long time;
    private String udid;

    private String subject;
    private String text;

    public ChatMessage() {
    }

    public ChatMessage(String from, String to, long time, String udid, String subject, String text) {
        this.from = from;
        this.to = to;
        this.time = time;
        this.udid = udid;
        this.subject = subject;
        this.text = text;
    }

    public ChatMessage setUp(String from, String to, long time, String udid) {
        return setFrom(from).setTo(to).setTime(time).setUdid(udid);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ChatMessage chatMessage = (ChatMessage) o;

        return getKey().equals(chatMessage.getKey());

    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "Message{" +
                "from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", time=" + time +
                ", udid='" + udid + '\'' +
                ", subject='" + subject + '\'' +
                ", text='" + text + '\'' +
                '}';
    }

    @Override
    public MessageKey getKey() {
        if(key == null) {
            key = new MessageKey(this);
        }
        return key;
    }

    public String getFrom() {
        return from;
    }

    public ChatMessage setFrom(String from) {
        this.from = from;
        return this;
    }

    public String getTo() {
        return to;
    }

    public ChatMessage setTo(String to) {
        this.to = to;
        return this;
    }

    public long getTime() {
        return time;
    }

    public ChatMessage setTime(long time) {
        this.time = time;
        return this;
    }

    public String getUdid() {
        return udid;
    }

    public ChatMessage setUdid(String udid) {
        this.udid = udid;
        return this;
    }

    public String getSubject() {
        return subject == null ? "" : subject;
    }

    public ChatMessage setSubject(String subject) {
        this.subject = subject;
        return this;
    }

    public String getText() {
        return text == null ? "" : text;
    }

    public ChatMessage setText(String text) {
        this.text = text;
        return this;
    }


}