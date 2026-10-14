package com.millionnaire.gateway.room;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.PublicEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
/** Bounded, room-local public outcome journal. Never stores draws, hands, query results or chat. */
final class PublicJournal {
    private static final Set<String> ALLOWED=Set.of("PropertyBought","PropertyUpgraded","RentPaid","RentWaived",
        "PropertyForceBought","TradeCompleted","AuctionSettled","AuctionPassed","AssetMortgaged","AssetRedeemed",
        "StartRewardPaid","EventRewardPaid","FeePaid","PlayerEliminated","PropertyBuilt","PropertyDowngraded",
        "PropertyDemolished","PropertyCleared","TeamAidTransferred","PlayerJailed","JailReleased");
    private final ArrayDeque<ObjectNode> entries=new ArrayDeque<>();
    private final ObjectMapper json=new ObjectMapper();
    private long sequence,gameNo=-1;
    void append(long game,List<Event> events,int limit){
        if(game!=gameNo){entries.clear();gameNo=game;}
        for(Event e:events){
            String kind=e.getClass().getSimpleName();
            if(!(e instanceof PublicEvent)||!ALLOWED.contains(kind))continue;
            ObjectNode node=json.createObjectNode().put("id",++sequence).put("gameNo",game).put("kind",kind);
            node.set("data",json.valueToTree(e));entries.addLast(node);
        }
        while(entries.size()>limit)entries.removeFirst();
    }
    List<ObjectNode> snapshot(){return entries.stream().map(ObjectNode::deepCopy).toList();}
}
