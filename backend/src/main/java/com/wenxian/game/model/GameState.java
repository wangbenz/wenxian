package com.wenxian.game.model;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    public String sceneId;
    public int minute;
    public int day;
    public int turn;
    public List<String> narrative = new ArrayList<>();
    public List<String> signals = new ArrayList<>();
    public PlayerState player;
    public List<CharacterRecord> characters = new ArrayList<>();
    public List<ChronicleEvent> events = new ArrayList<>();
    public List<QuestThread> threads = new ArrayList<>();

    public static class PlayerState {
        public String name;
        public String realm;
        public String constitution;
        public int vitality;
        public int silver;
        public int insight;
        public int suspicion;

        public PlayerState() {}

        public PlayerState(String name, String realm, String constitution, int vitality, int silver, int insight, int suspicion) {
            this.name = name;
            this.realm = realm;
            this.constitution = constitution;
            this.vitality = vitality;
            this.silver = silver;
            this.insight = insight;
            this.suspicion = suspicion;
        }
    }

    public static class CharacterRecord {
        public String id;
        public String name;
        public String identity;
        public String state;
        public String location;
        public String relation;
        public int lastSeenAt;

        public CharacterRecord() {}

        public CharacterRecord(String id, String name, String identity, String state, String location, String relation, int lastSeenAt) {
            this.id = id;
            this.name = name;
            this.identity = identity;
            this.state = state;
            this.location = location;
            this.relation = relation;
            this.lastSeenAt = lastSeenAt;
        }
    }

    public static class ChronicleEvent {
        public String id;
        public String title;
        public String detail;
        public int at;
        public String kind;

        public ChronicleEvent() {}

        public ChronicleEvent(String id, String title, String detail, int at, String kind) {
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.at = at;
            this.kind = kind;
        }
    }

    public static class QuestThread {
        public String id;
        public String title;
        public String state;
        public int danger;

        public QuestThread() {}

        public QuestThread(String id, String title, String state, int danger) {
            this.id = id;
            this.title = title;
            this.state = state;
            this.danger = danger;
        }
    }
}
