package com.latestay.conversation;

public record ChatMessage(Sender from, String text) {

    public enum Sender {
        TRAVELLER,
        HOTEL
    }

    public static ChatMessage traveller(String text) {
        return new ChatMessage(Sender.TRAVELLER, text);
    }

    public static ChatMessage hotel(String text) {
        return new ChatMessage(Sender.HOTEL, text);
    }
}
