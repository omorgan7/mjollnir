package mjollnir;

import skadistats.clarity.Clarity;
import skadistats.clarity.wire.common.proto.Demo.CDemoFileInfo;
import skadistats.clarity.wire.common.proto.Demo.CGameInfo;
import skadistats.clarity.wire.common.proto.Demo.CGameInfo.CDotaGameInfo;
import skadistats.clarity.wire.common.proto.Demo.CGameInfo.CDotaGameInfo.CPlayerInfo;
import skadistats.clarity.decoder.Util;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityUpdated;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.modifiers.OnModifierTableEntry;
import skadistats.clarity.wire.common.proto.DotaModifiers;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.model.StringTable;
import skadistats.clarity.processor.stringtables.UsesStringTable;
import skadistats.clarity.processor.stringtables.StringTables;
import skadistats.clarity.source.MappedFileSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;


@UsesEntities
public class Main {

    private final SimpleRunner runner;
    private Entities processor;

    private HashSet<String> itemsOfInterest = new HashSet<>(Arrays.asList("CDOTA_Item_Moonshard", "CDOTA_Item_UltimateScepter_2", "CDOTA_Item_Tome_Of_Knowledge"));
    private HashSet<String> ignoredHeroes = new HashSet<>(Arrays.asList("CDOTA_Unit_Courier", "CDOTA_Unit_Roshan"));

    private int radiantKills = 0;
    private int direKills = 0;

    private HashMap<Integer, HashMap<String, HashSet<Integer>>> playerBuffMap = new HashMap<>();
    public static void main(String[] args) throws Exception {
        CDemoFileInfo info = Clarity.infoForFile(args[0]);

        HashMap<String, Object> outInfo = new HashMap<>();
        CGameInfo gameInfo = info.getGameInfo();
        CDotaGameInfo dota = gameInfo.getDota();

        outInfo.put("game_winner", getTeamName(dota.getGameWinner()));
        outInfo.put("game_mode", dota.getGameMode());
        outInfo.put("game_timestamp", dota.getEndTime());

        List<CPlayerInfo> players = dota.getPlayerInfoList();

        List<HashMap<String, Object>> outPlayers = new ArrayList<HashMap<String, Object>>();

        outInfo.put("players", outPlayers);

        for (CPlayerInfo player : players)
        {
            HashMap<String, Object> newPlayer = new HashMap<>();
            
            outPlayers.add(newPlayer);

            newPlayer.put("hero_name", player.getHeroName());
            newPlayer.put("player_name", player.getPlayerName());
            newPlayer.put("steam_id", Long.toString(player.getSteamid()));
            newPlayer.put("game_team", player.getGameTeam());
        }
        
        Main m = new Main(args[0]);
        m.showScoreboard(outInfo);

        ObjectMapper mapper = new ObjectMapper();

        String jsonOut = mapper.writeValueAsString(outInfo);

        String fileName = outInfo.get("match_id") + ".json";

        FileWriter file = new FileWriter(fileName);
        file.write(jsonOut);
        file.flush();
        file.close();
        System.out.println(fileName);
    }

    private void AddEntity(Entity entity, Entity parent)
    {
        if (ignoredHeroes.contains(parent.getDtClass().getDtName()))
        {
            return;
        }

        HashMap<String, HashSet<Integer>> buffMap = playerBuffMap.get(parent.getDtClass().getClassId());
        if (buffMap == null)
        {
            buffMap = new HashMap<>();
        }

        String entityName = entity.getDtClass().getDtName();
        HashSet<Integer> buffInstances = buffMap.get(entityName);

        if (buffInstances == null)
        {
            buffInstances = new HashSet<>();
        }
        
        // alchemist could gift scepters.
        // anyone can gift moonshards.
        // gifting count as a buff for the giver
        // so we need to filter multiple instances of these out.
        if (buffInstances.size() == 0 || entity.getDtClass().getDtName().contentEquals("CDOTA_Item_Tome_Of_Knowledge"))
        {
            buffInstances.add(entity.getHandle());
        }

        buffMap.put(entityName, buffInstances);
        playerBuffMap.put(parent.getDtClass().getClassId(), buffMap);
    }

    public Main(String fileName) throws IOException, InterruptedException
    {
        runner = new SimpleRunner(new MappedFileSource(fileName));
        runner.runWith(this);
    }

    @OnEntityUpdated
    public void onEntityUpdated(Entity entity, FieldPath[] _, int __)
    {
        if (processor == null)
        {
            processor = runner.getContext().getProcessor(Entities.class);
        }

        if (entity.getDtClass().getDtName().contains("CDOTATeam"))
        {
            int gameTeam = (int) entity.getProperty("m_iTeamNum");

            if (gameTeam == 3)
            {
                direKills = (int) entity.getProperty("m_iHeroKills");
            }
            else if (gameTeam == 2)
            {
                radiantKills = (int) entity.getProperty("m_iHeroKills");
            }
        }
        if (itemsOfInterest.contains(entity.getDtClass().getDtName()))
        {
            Entity parent = processor.getByHandle((int) entity.getProperty("m_hOwnerEntity"));
            AddEntity(entity, parent);
        }
    }

    // This seems to be necessary in addition to onEntityUpdated method.
    @OnModifierTableEntry()
    public void onModifierEntry(DotaModifiers.CDOTAModifierBuffTableEntry modifier) {
        if (processor == null)
        {
            processor = runner.getContext().getProcessor(Entities.class);
        }

        Entity entity = processor.getByHandle(modifier.getAbility());

        if (entity == null)
        {
            return;
        }

        if (itemsOfInterest.contains(entity.getDtClass().getDtName()))
        {
            Entity parent = processor.getByHandle(modifier.getParent());
            if (parent == null)
            {
                return;
            }

            AddEntity(entity, parent);
        }
    }

    @UsesStringTable("EntityNames")
    private void showScoreboard(HashMap<String, Object> gameInfo) {

        Context ctx = runner.getContext();
        Entity e = ctx.getProcessor(Entities.class).getByDtName("CDOTAGamerulesProxy");
        
        long match_id = ((Number) e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_unMatchID64"))).longValue();
        gameInfo.put("match_id", match_id);

        float endTime = e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_flGameEndTime"));
        float startTime = e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_flGameStartTime"));

        float gameTime = endTime - startTime;
        float gameTimeMinutes = gameTime / 60.0f;

        gameInfo.put("game_time", gameTime);

        @SuppressWarnings("unchecked")
        List<HashMap<String, Object>> players = (List<HashMap<String, Object>>) gameInfo.get("players");

        StringTable stEntityNames = ctx.getProcessor(StringTables.class).forName("EntityNames");
        Entities entities = ctx.getProcessor(Entities.class);
        Entity pr = ctx.getProcessor(Entities.class).getByDtName("CDOTA_PlayerResource");
        Entity radiant = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataRadiant");
        Entity dire = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataDire");

        gameInfo.put("radiant_kills", radiantKills);
        gameInfo.put("dire_kills", direKills);

        int radCaptainId = (int) e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_iCaptainPlayerIDs.0000"));
        int direCaptainId = (int) e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_iCaptainPlayerIDs.0001"));

        for (int h = 0; h < 10; ++h)
        {
            
            int handle = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_hSelectedHero", h);
            Entity eHero = ctx.getProcessor(Entities.class).getByHandle(handle);

            int level = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iLevel", h);
            int kills = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iKills", h);
            int deaths = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iDeaths", h);
            int assists = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iAssists", h);

            Entity data = h < 5 ? radiant : dire;

            int gold = getEntityProperty(data, "m_vecDataTeam.%i.m_iTotalEarnedGold", h % 5);
            int lastHits = getEntityProperty(data, "m_vecDataTeam.%i.m_iLastHitCount", h % 5);
            int denies = getEntityProperty(data, "m_vecDataTeam.%i.m_iDenyCount", h % 5);
            int xp = getEntityProperty(data, "m_vecDataTeam.%i.m_iTotalEarnedXP", h % 5);
            float healing = getEntityProperty(data, "m_vecDataTeam.%i.m_fHealing", h % 5);
            int damage = getEntityProperty(data, "m_vecDataTeam.%i.m_iHeroDamage", h % 5);

            HashMap<String, Object> player = players.get(h);
            player.put("level", level);
            player.put("kills", kills);
            player.put("deaths", deaths);
            player.put("assists", assists);
            player.put("gpm", gold / gameTimeMinutes);
            player.put("last_hits", lastHits);
            player.put("denies", denies);
            player.put("xpm", xp / gameTimeMinutes);
            player.put("healing", healing);
            player.put("damage", damage);

            if ((h == radCaptainId || h == direCaptainId) && radCaptainId != direCaptainId)
            {
                player.put("captain", "1");
            }

            List<String> items = new ArrayList<String>();

            // put 19 items for now, investigate to see if this changes in future.
            for (int i = 0; i < 19; ++i)
            {
                Integer hItem = eHero.getProperty("m_hItems." + Util.arrayIdxToString(i));
                if (hItem == null) continue;
                
                Entity eItem = entities.getByHandle(hItem);
                if (eItem == null) continue;

                int idx = eItem.getProperty("m_pEntity.m_nameStringableIndex");
                String itemName = stEntityNames.getNameByIndex(idx);
                items.add(itemName);
            }

            player.put("items", items);
            
            HashMap<String, Integer> buffs = new HashMap<>();
            HashMap<String, HashSet<Integer>> buffMap = playerBuffMap.get(eHero.getDtClass().getClassId());
            
            // this player had no buffs.
            if (buffMap == null)
            {
                continue;
            }

            Iterator<Entry<String, HashSet<Integer>>> it = buffMap.entrySet().iterator();

            while (it.hasNext())
            {
                Map.Entry<String, HashSet<Integer>> mapElement = (Map.Entry<String, HashSet<Integer>>)it.next();
                buffs.put(mapElement.getKey(), mapElement.getValue().size());
            }

            player.put("buffs", buffs);
        }
    }

    public <T> T getEntityProperty(Entity e, String property, Integer idx) {
    	try {
	        if (e == null) {
	            return null;
	        }
	        if (idx != null) {
	            property = property.replace("%i", Util.arrayIdxToString(idx));
	        }
	        FieldPath fp = e.getDtClass().getFieldPathForName(property);
	        return e.getPropertyForFieldPath(fp);
    	}
    	catch (Exception ex) {
    		return null;
    	}
    }

    private static String getTeamName(int team) {
        switch(team) {
            case 2:
                return "Radiant";
            case 3:
                return "Dire";
            default:
                return "";
        }
    }
}
