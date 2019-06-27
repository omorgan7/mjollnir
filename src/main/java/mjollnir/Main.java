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
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.runner.ControllableRunner;
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
import java.util.ArrayList;

@UsesEntities
public class Main {

    public static void main(String[] args) throws Exception {
        CDemoFileInfo info = Clarity.infoForFile(args[0]);

        HashMap<String, Object> outInfo = new HashMap<>();
        CGameInfo gameInfo = info.getGameInfo();
        CDotaGameInfo dota = gameInfo.getDota();

        outInfo.put("match_id", dota.getMatchId());
        outInfo.put("game_winner", getTeamName(dota.getGameWinner()));
        outInfo.put("game_mode", dota.getGameMode());

        List<CPlayerInfo> players = dota.getPlayerInfoList();

        List<HashMap<String, Object>> outPlayers = new ArrayList<HashMap<String, Object>>();

        outInfo.put("players", outPlayers);

        for (CPlayerInfo player : players)
        {
            HashMap<String, Object> newPlayer = new HashMap<>();
            
            outPlayers.add(newPlayer);

            newPlayer.put("hero_name", player.getHeroName());
            newPlayer.put("player_name", player.getPlayerName());
            newPlayer.put("steam_id", player.getSteamid());
            newPlayer.put("game_team", player.getGameTeam());
        }
        
        new Main(args[0]).showScoreboard(outInfo);

        ObjectMapper mapper = new ObjectMapper();

        String jsonOut = mapper.writeValueAsString(outInfo);

        String fileName = outInfo.get("match_id") + ".json";

        FileWriter file = new FileWriter(fileName);
        file.write(jsonOut);
        file.flush();
        file.close();
        System.out.println(fileName);
    }

    private final ControllableRunner runner;

    public Main(String fileName) throws IOException, InterruptedException {
        runner = new ControllableRunner(new MappedFileSource(fileName)).runWith(this);
        runner.seek(runner.getLastTick());
        runner.halt();
    }

    @UsesStringTable("EntityNames")
    private void showScoreboard(HashMap<String, Object> gameInfo) {

        Context ctx = runner.getContext();
        Entity e = ctx.getProcessor(Entities.class).getByDtName("CDOTAGamerulesProxy");
        
        float endTime = e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_flGameEndTime"));
        float startTime = e.getPropertyForFieldPath(e.getDtClass().getFieldPathForName("m_pGameRules.m_flGameStartTime"));

        float gameTime = endTime - startTime;
        float gameTimeMinutes = gameTime / 60.0f;

        gameInfo.put("game_time", gameTime);

        List<HashMap<String, Object>> players = (List<HashMap<String, Object>>) gameInfo.get("players");

        StringTable stEntityNames = ctx.getProcessor(StringTables.class).forName("EntityNames");
        Entities entities = ctx.getProcessor(Entities.class);
        Entity pr = ctx.getProcessor(Entities.class).getByDtName("CDOTA_PlayerResource");
        Entity radiant = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataRadiant");
        Entity dire = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataDire");

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
            
            List<String> items = new ArrayList<String>();

            for (int i = 0; i < 9; ++i)
            {
                Integer hItem = eHero.getProperty("m_hItems." + Util.arrayIdxToString(i));
                if (hItem != 0xFFFFFF) {
                    Entity eItem = entities.getByHandle(hItem);
                    int idx = eItem.getProperty("m_pEntity.m_nameStringableIndex");
                    String itemName = stEntityNames.getNameByIndex(idx);
                    items.add(itemName);
                }
            }

            player.put("items", items);
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
