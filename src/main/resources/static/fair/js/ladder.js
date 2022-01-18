let rankerTemplate = {
    accountId: 0,
    username: "",
    points: new Decimal(0),
    power: new Decimal(0),
    bias: 0,
    multiplier: 0,
    you: false,
    growing: true,
    timesAsshole: 0,
    grapes: new Decimal(0),          // only shows the actual Number on yourRanker
    vinegar: new Decimal(0)          // only shows the actual Number on yourRanker
}


let ladderData = {
    rankers: [rankerTemplate],
    currentLadder: {number: 1},
    firstRanker: rankerTemplate,
    yourRanker: rankerTemplate
};

let ladderStats = {
    growingRankerCount: 0
}

function initLadder(ladderNum) {
    stompClient.send("/app/ladder/init/" + ladderNum, {}, JSON.stringify({
        'uuid': getCookie("_uuid")
    }));
}

function handleLadderInit(message) {
    if (message.status === "OK") {
        if (message.content) {
            console.log(message);
            ladderData = message.content;
            ladderData.rankers.forEach(ranker => {
                ranker.power = new Decimal(ranker.power);
                ranker.points = new Decimal(ranker.points);

                if (ranker.you) {
                    ranker.grapes = new Decimal(ranker.grapes);
                    ranker.vinegar = new Decimal(ranker.vinegar);
                } else {
                    ranker.grapes = new Decimal(0);
                    ranker.vinegar = new Decimal(0);
                }
            })

            ladderData.yourRanker.power = new Decimal(ladderData.yourRanker.power);
            ladderData.yourRanker.points = new Decimal(ladderData.yourRanker.points);
            ladderData.yourRanker.grapes = new Decimal(ladderData.yourRanker.grapes);
            ladderData.yourRanker.vinegar = new Decimal(ladderData.yourRanker.vinegar);

            ladderData.firstRanker.power = new Decimal(ladderData.firstRanker.power);
            ladderData.firstRanker.points = new Decimal(ladderData.firstRanker.points);
            ladderData.firstRanker.grapes = new Decimal(0);
            ladderData.firstRanker.vinegar = new Decimal(0);
        }
    }
    updateLadder();
}

function buyBias() {
    $('#biasButton').prop("disabled", true);
    $('#biasTooltip').tooltip('hide');
    let cost = new Decimal(getUpgradeCost(ladderData.yourRanker.bias + 1));
    if (ladderData.yourRanker.points.compare(cost) > 0) {
        stompClient.send("/app/ladder/post/bias", {}, JSON.stringify({
            'uuid': identityData.uuid
        }));
    }
}

function buyMulti() {
    $('#multiButton').prop("disabled", true);
    $('#multiTooltip').tooltip('hide');
    let cost = getUpgradeCost(ladderData.yourRanker.multiplier + 1);
    if (ladderData.yourRanker.power.compare(cost) > 0) {
        stompClient.send("/app/ladder/post/multi", {}, JSON.stringify({
            'uuid': identityData.uuid
        }));
    }
}

function throwVinegar() {
    if (ladderData.yourRanker.vinegar.cmp(getVinegarThrowCost()) >= 0) {
        stompClient.send("/app/ladder/post/vinegar", {}, JSON.stringify({
            'uuid': identityData.uuid
        }));
    }
}

function promote() {
    $('#promoteButton').hide();
    stompClient.send("/app/ladder/post/promote", {}, JSON.stringify({
        'uuid': identityData.uuid
    }));
}

function beAsshole() {
    if (ladderData.firstRanker.you && ladderData.rankers.length >= Math.max(infoData.minimumPeopleForPromote, ladderData.currentLadder.number)
        && ladderData.firstRanker.points.cmp(infoData.pointsForPromote) >= 0
        && ladderData.currentLadder.number >= infoData.assholeLadder) {
        if (confirm("Do you really wanna be an Asshole?!")) {
            stompClient.send("/app/ladder/post/asshole", {}, JSON.stringify({
                'uuid': identityData.uuid
            }));
        }
    }
}

function handleLadderUpdates(message) {
    if (message) {
        message.events.forEach(e => handleEvent(e))
    }
    calculateLadder(message.secondsPassed);
    updateLadder();
}

function changeLadder(ladderNum) {
    if (ladderSubscription) ladderSubscription.unsubscribe();
    ladderSubscription = stompClient.subscribe('/topic/ladder/' + ladderNum,
        (message) => handleLadderUpdates(JSON.parse(message.body)), {uuid: getCookie("_uuid")});
    initLadder(ladderNum);
}

function handleEvent(event) {
    switch (event.eventType) {
        case 'BIAS':
            handleBias(event);
            break;
        case 'MULTI':
            handleMultiplier(event);
            break;
        case 'VINEGAR':
            handleVinegar(event);
            break;
        case 'PROMOTE':
            handlePromote(event);
            break;
        case 'JOIN':
            handleJoin(event);
            break;
        case 'NAMECHANGE':
            handleNameChange(event);
            break;
        case 'RESET':
            handleReset(event);
            break;
    }
}

function handleBias(event) {
    ladderData.rankers.forEach(ranker => {
        if (event.accountId === ranker.accountId) {
            ranker.bias += 1;
            ranker.points = new Decimal(0);
        }
    });
}

function handleMultiplier(event) {
    ladderData.rankers.forEach(ranker => {
        if (event.accountId === ranker.accountId) {
            ranker.multiplier += 1;
            ranker.bias = 0;
            ranker.points = new Decimal(0);
            ranker.power = new Decimal(0);
        }
    });
}

function handleVinegar(event) {
    ladderData.rankers.forEach(ranker => {
        if (event.accountId === ranker.accountId) {
            ranker.vinegar = new Decimal(0);
        }
    });

    let vinegarThrown = new Decimal(event.vinegarThrown);
    ladderData.rankers[0].vinegar = Decimal.max(ladderData.rankers[0].vinegar.sub(vinegarThrown), 0);
}

function handlePromote(event) {
    ladderData.rankers.forEach(ranker => {
        if (event.accountId === ranker.accountId) {
            ranker.growing = false;
        }
    });

    if (event.accountId === identityData.accountId) {
        let newLadderNum = ladderData.currentLadder.number + 1
        changeLadder(newLadderNum);
        changeChatRoom(newLadderNum);
    }
}

function handleJoin(event) {
    let newRanker = {
        accountId: event.accountId,
        username: event.joinData.username,
        points: new Decimal(0),
        power: new Decimal(1),
        bias: 0,
        multiplier: 1,
        you: false,
        growing: true,
        timesAsshole: event.joinData.timesAsshole,
        grapes: new Decimal(0),          // only shows the actual Number on yourRanker
        vinegar: new Decimal(0)          // only shows the actual Number on yourRanker
    }

    if (newRanker.accountId !== identityData.accountId)
        ladderData.rankers.push(newRanker);
}

function handleNameChange(event) {
    ladderData.rankers.forEach(ranker => {
        if (event.accountId === ranker.accountId) {
            ranker.username = event.changedUsername;
        }
    })
    updateChatUsername(event);
}

async function handleReset(event) {
    disconnect();
    await new Promise(r => setTimeout(r, 2000));
    location.reload();
}

function calculateLadder(delta) {
    ladderData.rankers = ladderData.rankers.sort((a, b) => b.points.sub(a.points));
    ladderStats.growingRankerCount = 0;
    for (let i = 0; i < ladderData.rankers.length; i++) {
        ladderData.rankers[i].rank = i + 1;
        // If the ranker is currently still on ladder
        if (ladderData.rankers[i].growing) {
            ladderStats.growingRankerCount += 1;
            // Calculating Points & Power
            if (ladderData.rankers[i].rank !== 1)
                ladderData.rankers[i].power = ladderData.rankers[i].power.add(
                    new Decimal((ladderData.rankers[i].bias + ladderData.rankers[i].rank - 1) * ladderData.rankers[i].multiplier)
                        .mul(new Decimal(delta).floor()));
            ladderData.rankers[i].points = ladderData.rankers[i].points.add(ladderData.rankers[i].power.mul(delta).floor());

            // Calculating Vinegar based on Grapes count
            ladderData.rankers[i].vinegar = ladderData.rankers[i].vinegar.add(ladderData.rankers[i].grapes.mul(delta).floor());

            for (let j = i - 1; j >= 0; j--) {
                // If one of the already calculated Rankers have less points than this ranker
                // swap these in the list... This way we keep the list sorted, theoretically
                let currentRanker = ladderData.rankers[j + 1];
                if (currentRanker.points.cmp(ladderData.rankers[j].points) > 0) {
                    // Move 1 Position up and move the ranker there 1 Position down

                    // Move other Ranker 1 Place down
                    ladderData.rankers[j].rank = j + 2;
                    if (ladderData.rankers[j].growing && ladderData.rankers[j].you && (ladderData.rankers[j].bias > 0 || ladderData.rankers[j].multiplier > 1))
                        ladderData.rankers[j].grapes = ladderData.rankers[j].grapes.add(new Decimal(1));
                    ladderData.rankers[j + 1] = ladderData.rankers[j];

                    // Move this Ranker 1 Place up
                    currentRanker.rank = j + 1;
                    ladderData.rankers[j] = currentRanker;
                } else {
                    break;
                }
            }
        }
    }

    // Ranker on Last Place gains 1 Grape, only if he isn't the only one
    if (ladderData.rankers.length >= Math.max(infoData.minimumPeopleForPromote, ladderData.currentLadder.number)) {
        let index = ladderData.rankers.length - 1;
        if (ladderData.rankers[index].growing)
            ladderData.rankers[index].grapes = ladderData.rankers[index].grapes.add(new Decimal(1).mul(delta).floor());
    }

    ladderData.rankers.forEach(ranker => {
        if (ranker.you) {
            ladderData.yourRanker = ranker;
        }
    })
    ladderData.firstRanker = ladderData.rankers[0];
}

function updateLadder() {
    let size = ladderData.rankers.length;
    let rank = ladderData.yourRanker.rank;
    let ladderArea = Math.floor(rank / clientData.ladderAreaSize);

    let startRank = (ladderArea * clientData.ladderAreaSize) - clientData.ladderPadding;
    let endRank = startRank + clientData.ladderAreaSize - 1 + (2 * clientData.ladderPadding);

    let body = document.getElementById("ladderBody");
    body.innerHTML = "";
    if (startRank > 1) writeNewRow(body, ladderData.firstRanker);
    for (let i = 0; i < ladderData.rankers.length; i++) {
        let ranker = ladderData.rankers[i];
        if ((ranker.rank >= startRank && ranker.rank <= endRank)) writeNewRow(body, ranker);
    }

    // if we dont have enough Ranker yet, fill the table with filler rows
    for (let i = body.rows.length; i < clientData.ladderAreaSize + clientData.ladderPadding * 2; i++) {
        writeNewRow(body, rankerTemplate);
        body.rows[i].style.visibility = 'hidden';
    }

    let tag1 = '', tag2 = '';
    if (ladderData.yourRanker.vinegar.cmp(getVinegarThrowCost()) >= 0) {
        tag1 = '<p style="color: plum">'
        tag2 = '</p>'
    }

    $('#infoText').html('Sour Grapes: ' + numberFormatter.format(ladderData.yourRanker.grapes) + '<br>' + tag1 + 'Vinegar: ' + numberFormatter.format(ladderData.yourRanker.vinegar) + tag2);

    $('#usernameLink').html(ladderData.yourRanker.username);
    $('#usernameText').html("+" + ladderData.yourRanker.bias + "   x" + ladderData.yourRanker.multiplier);

    $('#rankerCount').html("Rankers: " + ladderStats.growingRankerCount + "/" + ladderData.rankers.length);
    $('#ladderNumber').html("Ladder # " + ladderData.currentLadder.number);

    let offCanvasBody = $('#offCanvasBody');
    offCanvasBody.empty();
    for (let i = 1; i <= ladderData.currentLadder.number; i++) {
        let ladder = $(document.createElement('li')).prop({
            class: "nav-link"
        });

        let ladderLinK = $(document.createElement('a')).prop({
            href: '#',
            innerHTML: 'Chad #' + i,
            class: "nav-link h5"
        });

        ladderLinK.click(async function () {
            changeChatRoom(i);
        })

        ladder.append(ladderLinK);
        offCanvasBody.prepend(ladder);
    }

    showButtons();
}

function writeNewRow(body, ranker) {
    let row = body.insertRow();
    let assholeTag = (ranker.timesAsshole < infoData.assholeTags.length) ?
        infoData.assholeTags[ranker.timesAsshole] : infoData.assholeTags[infoData.assholeTags.length - 1];
    let rank = (ranker.rank === 1 && !ranker.you && ranker.growing && ladderData.rankers.length >= Math.max(infoData.minimumPeopleForPromote, ladderData.currentLadder.number)
        && ladderData.firstRanker.points.cmp(infoData.pointsForPromote) >= 0) ?
        '<a href="#" style="text-decoration: none" onclick="throwVinegar()">🍇</a>' : ranker.rank;
    row.insertCell(0).innerHTML = rank + assholeTag;
    row.insertCell(1).innerHTML = ranker.username;
    row.cells[1].style.overflow = "hidden";
    row.insertCell(2).innerHTML = numberFormatter.format(ranker.power) +
        ' [+' + ('' + ranker.bias).padStart(2, '0') + ' x' + ('' + ranker.multiplier).padStart(2, '0') + ']';
    row.cells[2].classList.add('text-end');
    row.insertCell(3).innerHTML = numberFormatter.format(ranker.points);
    row.cells[3].classList.add('text-end');
    if (ranker.you) row.classList.add('table-active');
    return row;
}

function showButtons() {
    let biasButton = $('#biasButton');
    let multiButton = $('#multiButton');

    let biasCost = getUpgradeCost(ladderData.yourRanker.bias + 1);
    if (ladderData.yourRanker.points.cmp(biasCost) >= 0) {
        biasButton.prop("disabled", false);
    } else {
        biasButton.prop("disabled", true);
    }

    let multiCost = getUpgradeCost(ladderData.yourRanker.multiplier + 1);
    if (ladderData.yourRanker.power.cmp(new Decimal(multiCost)) >= 0) {
        multiButton.prop("disabled", false);
    } else {
        multiButton.prop("disabled", true);
    }
    $('#biasTooltip').attr('data-bs-original-title', numberFormatter.format(biasCost) + ' Points');
    $('#multiTooltip').attr('data-bs-original-title', numberFormatter.format(multiCost) + ' Power');

    let promoteButton = $('#promoteButton');
    let assholeButton = $('#assholeButton');
    let ladderNumber = $('#ladderNumber');

    if (ladderData.firstRanker.you && ladderData.rankers.length >= Math.max(infoData.minimumPeopleForPromote, ladderData.currentLadder.number)
        && ladderData.firstRanker.points.cmp(infoData.pointsForPromote) >= 0) {
        if (ladderData.currentLadder.number === infoData.assholeLadder) {
            promoteButton.hide()
            ladderNumber.hide()
            assholeButton.show()
        } else {
            assholeButton.hide()
            ladderNumber.hide()
            promoteButton.show()
        }
    } else {
        assholeButton.hide()
        promoteButton.hide()
        ladderNumber.show()
    }
}