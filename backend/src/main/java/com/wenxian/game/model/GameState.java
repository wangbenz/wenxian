package com.wenxian.game.model;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    public String phase = "setup";
    public String difficulty = "NORMAL";
    public String saveId;
    public String sceneId;
    public String sceneTitle;
    public String currentLocation;
    public String currentLocationId;
    public int worldYear;
    public int minute;
    public int day;
    public int turn;
    public int economyVersion;
    public Wallet wallet;
    public List<String> narrative = new ArrayList<>();
    public List<String> signals = new ArrayList<>();
    public List<StoryChoice> choices = new ArrayList<>();
    public WorldProfile world;
    public CreationProfile creationProfile;
    public List<ProtagonistOption> protagonistOptions = new ArrayList<>();
    public List<AbilityRecord> talentOptions = new ArrayList<>();
    public boolean talentRerolled;
    public PlayerState player;
    public List<CharacterRecord> characters = new ArrayList<>();
    public List<ChronicleEvent> events = new ArrayList<>();
    public List<QuestThread> threads = new ArrayList<>();
    public List<InventoryItem> inventory = new ArrayList<>();
    public List<AbilityRecord> abilities = new ArrayList<>();
    public List<LocationRecord> knownLocations = new ArrayList<>();
    public DeathRecord death;

    public static class Wallet {
        /** 凡俗钱财统一以“文”为最小记账单位。 */
        public long copperWen;
        public long lowSpiritStones;
        public long middleSpiritStones;
        public long highSpiritStones;

        public Wallet() {}

        public Wallet(long copperWen, long lowSpiritStones, long middleSpiritStones, long highSpiritStones) {
            this.copperWen = copperWen;
            this.lowSpiritStones = lowSpiritStones;
            this.middleSpiritStones = middleSpiritStones;
            this.highSpiritStones = highSpiritStones;
        }
    }

    public static class CreationProfile {
        public String race;
        public String gender;
        public String lifeGoal;
        public String originPremise;
        public String storyPreference;

        public CreationProfile() {}

        public CreationProfile(String race, String gender, String lifeGoal,
                               String originPremise, String storyPreference) {
            this.race = race;
            this.gender = gender;
            this.lifeGoal = lifeGoal;
            this.originPremise = originPremise;
            this.storyPreference = storyPreference;
        }
    }

    public static class LocationRecord {
        public String id;
        public String name;
        public String scope;
        public String parentId;
        public int discoveredAt;

        public LocationRecord() {}

        public LocationRecord(String id, String name, String scope, String parentId, int discoveredAt) {
            this.id = id;
            this.name = name;
            this.scope = scope;
            this.parentId = parentId;
            this.discoveredAt = discoveredAt;
        }
    }

    public static class DeathRecord {
        public String title;
        public String detail;
        public int at;
        public String sceneTitle;
        public String location;
        public int turn;

        public DeathRecord() {}

        public DeathRecord(String title, String detail, int at, String sceneTitle, String location, int turn) {
            this.title = title;
            this.detail = detail;
            this.at = at;
            this.sceneTitle = sceneTitle;
            this.location = location;
            this.turn = turn;
        }
    }

    public static class WorldProfile {
        public String id;
        public String title;
        public String era;
        public String summary;
        public String cultivationLaw;
        public String dangerRule;
        public List<String> regions = new ArrayList<>();
        public List<String> factions = new ArrayList<>();
        public List<RegionProfile> regionProfiles = new ArrayList<>();
        public List<FactionProfile> factionProfiles = new ArrayList<>();
        public String generatedBy;

        public WorldProfile() {}
    }

    public static class RegionProfile {
        public String id;
        public String name;
        public String description;
        public String dominantPower;
        public String travelRule;

        public RegionProfile() {}

        public RegionProfile(String id, String name, String description, String dominantPower, String travelRule) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.dominantPower = dominantPower;
            this.travelRule = travelRule;
        }
    }

    public static class FactionProfile {
        public String id;
        public String name;
        public String regionId;
        public String purpose;

        public FactionProfile() {}

        public FactionProfile(String id, String name, String regionId, String purpose) {
            this.id = id;
            this.name = name;
            this.regionId = regionId;
            this.purpose = purpose;
        }
    }

    public static class ProtagonistOption {
        public String id;
        public String name;
        public String race;
        public String gender;
        public String lifeGoal;
        public int age;
        public String identity;
        public String origin;
        public String originPremise;
        public String trait;
        public String goal;
        public String burden;
        public String constitution;
        public int vitality;
        public int silver;
        public int insight;
        public int suspicion;
        public OpeningSeed opening;

        public ProtagonistOption() {}
    }

    public static class OpeningSeed {
        public String sceneId;
        public String sceneTitle;
        public String location;
        public int day;
        public int minute;
        public List<String> narrative = new ArrayList<>();
        public List<String> signals = new ArrayList<>();
        public List<StoryChoice> choices = new ArrayList<>();
        public List<CharacterRecord> characters = new ArrayList<>();
        public List<QuestThread> threads = new ArrayList<>();

        public OpeningSeed() {}
    }

    public static class StoryChoice {
        public String id;
        public String label;
        public String hint;
        public String tone;
        public String actionType;
        public String targetCharacterId;
        public String targetRealm;
        public String riskLevel;

        public StoryChoice() {}

        public StoryChoice(String id, String label, String hint, String tone) {
            this(id, label, hint, tone, "OTHER", null, null,
                    "danger".equals(tone) ? "HIGH" : "MEDIUM");
        }

        public StoryChoice(String id, String label, String hint, String tone, String actionType,
                           String targetCharacterId, String targetRealm, String riskLevel) {
            this.id = id;
            this.label = label;
            this.hint = hint;
            this.tone = tone;
            this.actionType = actionType;
            this.targetCharacterId = targetCharacterId;
            this.targetRealm = targetRealm;
            this.riskLevel = riskLevel;
        }
    }

    public static class PlayerState {
        public String name;
        public String race;
        public String gender;
        public int age;
        public int lifespan;
        public String identity;
        public String origin;
        public String trait;
        public String originPremise;
        public String lifeGoal;
        public String goal;
        public String burden;
        public String realm;
        public String constitution;
        public String condition = "STABLE";
        public int vitality;
        public int silver;
        public int insight;
        public int suspicion;

        public PlayerState() {}

        public PlayerState(String name, String race, String gender, int age, int lifespan, String identity, String lifeGoal,
                           String goal, String burden, String realm,
                           String constitution, int vitality, int silver, int insight, int suspicion) {
            this.name = name;
            this.race = race;
            this.gender = gender;
            this.age = age;
            this.lifespan = lifespan;
            this.identity = identity;
            this.lifeGoal = lifeGoal;
            this.goal = goal;
            this.burden = burden;
            this.realm = realm;
            this.constitution = constitution;
            this.vitality = vitality;
            this.silver = silver;
            this.insight = insight;
            this.suspicion = suspicion;
        }
    }

    public static class InventoryItem {
        public String id;
        public String definitionId;
        public String name;
        public String category;
        public String quality;
        public int quantity;
        public String description;
        public boolean keyItem;

        public InventoryItem() {}

        public InventoryItem(String id, String definitionId, String name, String category, String quality,
                             int quantity, String description, boolean keyItem) {
            this.id = id;
            this.definitionId = definitionId;
            this.name = name;
            this.category = category;
            this.quality = quality;
            this.quantity = quantity;
            this.description = description;
            this.keyItem = keyItem;
        }
    }

    public static class AbilityRecord {
        public String id;
        public String definitionId;
        public String name;
        public String type;
        public String stage;
        public int mastery;
        public String source;
        public String description;
        public String rules;
        public int maxUses;
        public int remainingUses;
        public String resetRule;
        public int lastResetDay;
        public String sideEffect;

        public AbilityRecord() {}

        public AbilityRecord(String id, String definitionId, String name, String type, String stage,
                             int mastery, String source, String description) {
            this(id, definitionId, name, type, stage, mastery, source, description,
                    "依照能力描述生效", 0, 0, "NONE", 0, "无");
        }

        public AbilityRecord(String id, String definitionId, String name, String type, String stage,
                             int mastery, String source, String description, String rules,
                             int maxUses, int remainingUses, String resetRule, int lastResetDay,
                             String sideEffect) {
            this.id = id;
            this.definitionId = definitionId;
            this.name = name;
            this.type = type;
            this.stage = stage;
            this.mastery = mastery;
            this.source = source;
            this.description = description;
            this.rules = rules;
            this.maxUses = maxUses;
            this.remainingUses = remainingUses;
            this.resetRule = resetRule;
            this.lastResetDay = lastResetDay;
            this.sideEffect = sideEffect;
        }
    }

    public static class CharacterRecord {
        public String id;
        public String name;
        public String identity;
        public String realm = "UNKNOWN";
        public String condition = "STABLE";
        public String combatProfile = "常人";
        public String state;
        public String location;
        public String relation;
        public int lastSeenAt;

        public CharacterRecord() {}

        public CharacterRecord(String id, String name, String identity, String state, String location, String relation, int lastSeenAt) {
            this(id, name, identity, "UNKNOWN", "STABLE", "常人", state, location, relation, lastSeenAt);
        }

        public CharacterRecord(String id, String name, String identity, String realm, String condition,
                               String combatProfile, String state, String location, String relation, int lastSeenAt) {
            this.id = id;
            this.name = name;
            this.identity = identity;
            this.realm = realm;
            this.condition = condition;
            this.combatProfile = combatProfile;
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
