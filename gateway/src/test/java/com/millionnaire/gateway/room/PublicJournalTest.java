package com.millionnaire.gateway.room;
import com.millionnaire.engine.core.event.GameEvent.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PublicJournalTest{
    @Test void journalIsBoundedAndExcludesNonOutcomeData(){
        var j=new PublicJournal();
        j.append(1,List.of(new RentPaid("2","1",3,500),new PropertyBought("3",4,1000),
            new DiceRolled("1",6,false),new QueryRevealed("1","2",List.of(com.millionnaire.engine.config.CardType.BUILD))),1);
        assertThat(j.snapshot()).hasSize(1);
        var change=j.snapshot().getFirst();assertThat(change.get("kind").asText()).isEqualTo("PropertyBought");
        assertThat(change.get("data").has("price")).isTrue();
        change.put("kind","tampered");assertThat(j.snapshot().getFirst().get("kind").asText()).isEqualTo("PropertyBought");
        j.append(2,List.of(new RentPaid("1","2",4,100)),8);
        assertThat(j.snapshot()).hasSize(1);assertThat(j.snapshot().getFirst().get("gameNo").asLong()).isEqualTo(2);
    }
}
