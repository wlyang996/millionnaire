const test=require('node:test');
const assert=require('node:assert/strict');
const {botTeamDestination}=require('../temp/team-assignment-tests/BotTeamAssignment.js');

test('a full blue team sends the unassigned bot to red, including a host already on red',()=>{
    const team={teamSize:2,assignments:{host:'RED',bot1:'BLUE',bot2:'BLUE'}};
    assert.equal(botTeamDestination(team,'bot3'),'RED');
    assert.deepEqual(team.assignments,{host:'RED',bot1:'BLUE',bot2:'BLUE'});
});
test('both red seats can be filled after blue reaches capacity',()=>{
    const team={teamSize:2,assignments:{host:'BLUE',bot1:'BLUE'}};
    for(const bot of ['bot2','bot3']){
        const destination=botTeamDestination(team,bot);
        assert.equal(destination,'RED');
        team.assignments[bot]=destination;
    }
    assert.equal(botTeamDestination(team,'extra'),null);
});
test('uses the configured capacity for 2v2, 3v3 and 4v4 and preserves already assigned bots',()=>{
    for(const teamSize of [2,3,4]){
        const team={teamSize,assignments:{}};
        for(let i=0;i<teamSize*2;i++){
            const bot='bot'+i;
            const destination=botTeamDestination(team,bot);
            assert.equal(destination,i<teamSize?'BLUE':'RED');
            team.assignments[bot]=destination;
            assert.equal(botTeamDestination(team,bot),null);
        }
        assert.equal(botTeamDestination(team,'extra'),null);
    }
});
test('fills a newly available seat on either team after membership changes',()=>{
    const team={teamSize:2,assignments:{host:'RED',bot1:'BLUE',bot2:'BLUE',bot3:'RED'}};
    delete team.assignments.bot1;
    assert.equal(botTeamDestination(team,'newBot'),'BLUE');
    team.assignments.newBot='BLUE';
    delete team.assignments.bot3;
    assert.equal(botTeamDestination(team,'newBot2'),'RED');
});
