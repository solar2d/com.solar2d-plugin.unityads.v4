--
--  main.lua
--  UnityAds Sample App
--
--  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
--

local unityads = require("plugin.unityads.v4")
local widget = require("widget")
local json = require("json")

local appStatus = {
    useAndroidImmersive = false         -- sets android ui visibility to immersiveSticky to test hidden UI bar
}

--------------------------------------------------------------------------
-- set up UI
--------------------------------------------------------------------------

display.setStatusBar( display.HiddenStatusBar )
display.setDefault( "background", 1 )
if appStatus.useAndroidImmersive then
    native.setProperty( "androidSystemUiVisibility", "immersiveSticky")
end

local unityadLogo = display.newImage( "unityadslogo.png" )
unityadLogo.anchorY = 0
unityadLogo:scale( 0.4, 0.4 )

local setRed = function(self)
    self:setFillColor(1,0,0)
end

local setGreen = function(self)
    self:setFillColor(0,1,0)
end

local subTitle = display.newText {
    text = "plugin for Corona SDK",
    font = display.systemFont,
    fontSize = 14
}
subTitle:setTextColor( 0.2, 0.2, 0.2 )

eventDataTextBox = native.newTextBox( display.contentCenterX, display.contentHeight - 50, display.contentWidth - 10, 100)
eventDataTextBox.placeholder = "Event data will appear here"
eventDataTextBox.hasBackground = false

local processEventTable = function(event)
    local logString = json.prettify(event):gsub("\\","")
    logString = "\nPHASE: "..event.phase.." - - - - - - - - - \n" .. logString
    print(logString)
    eventDataTextBox.text = logString .. eventDataTextBox.text
end

-- --------------------------------------------------------------------------
-- -- plugin implementation
-- --------------------------------------------------------------------------

-- forward declarations
local gameId = "n/a"
local platformName = system.getInfo("platformName")
local vReady
local rReady

if platformName == "Android" then
    gameId="1225301"
elseif platformName == "iPhone OS" then
    gameId="1225300"
else
    print "Unsupported platform"
end

print("Game ID: "..gameId)

local unityadsListener = function(event)
    processEventTable(event)
    local data = (event .data ~= nil) and json.decode(event.data) or {}

    if (event.phase == "loaded") then
        if (data.placementId == "video") then
            setGreen(vReady)
        elseif (data.placementId == "rewardedVideo") then
            setGreen(rReady)
        end
    end
end

-- initialize UnityAds
unityads.setPersonalizedAds( true )
unityads.setPrivacyMode( "app" )
unityads.init(unityadsListener, {gameId=gameId, testMode=true})

-- test if ads are aleady available
timer.performWithDelay(3000, function()
    if (unityads.isLoaded("video")) then
        setGreen(vReady)
    end
    if (unityads.isLoaded("rewardedVideo")) then
        setGreen(rReady)
    end
end, -1)

local videoBG = display.newRect(0,0,320,30)
videoBG:setFillColor(0,0.7)

local videoLabel = display.newText {
    text = "V I D E O",
    font = display.systemFontBold,
    fontSize = 18,
}
videoLabel:setTextColor(1)

local showVideoButton = widget.newButton {
    label = "Show",
    width = 100,
    height = 40,
    labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
    onRelease = function(event)
        setRed(vReady)
        unityads.load("video")
        unityads.show("video")
        --unityads.setHasUserConsent(true)
    end
}

local rewardedBG = display.newRect(0,0,320,30)
rewardedBG:setFillColor(0,0.7)

local rewardedLabel = display.newText {
    text = "R E W A R D E D",
    font = display.systemFontBold,
    fontSize = 18,
}
rewardedLabel:setTextColor(1)

local showRewardedButton = widget.newButton {
    label = "Show",
    width = 100,
    height = 40,
    labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
    onRelease = function(event)
        setRed(rReady)
        unityads.load("rewardedVideo")
        unityads.show("rewardedVideo")
    end
}

vReady = display.newCircle(10, 10, 6)
vReady.strokeWidth = 2
vReady:setStrokeColor(0)
setRed(vReady)

rReady = display.newCircle(10, 10, 6)
rReady.strokeWidth = 2
rReady:setStrokeColor(0)
setRed(rReady)

-- -- --------------------------------------------------------------------------
-- -- -- device orientation handling
-- -- --------------------------------------------------------------------------

local layoutDisplayObjects = function(orientation)
    unityadLogo.x, unityadLogo.y = display.contentCenterX, 0

    subTitle.x = display.contentCenterX
    subTitle.y = 60

    if (orientation == "portrait") then
        eventDataTextBox.x = display.contentCenterX
        eventDataTextBox.y = display.contentHeight - 50
        eventDataTextBox.width = display.contentWidth - 10
    else
        -- put it waaaay offscreen
        eventDataTextBox.y = 2000
    end

    videoBG.x, videoBG.y = display.contentCenterX, 140
    videoBG:setFillColor(0,0.7)

    videoLabel.x = display.contentCenterX
    videoLabel.y = 140

    vReady.x = display.contentCenterX + 140
    vReady.y = 140
    setRed(vReady)

    showVideoButton.x = display.contentCenterX
    showVideoButton.y = videoLabel.y + 40

    rewardedBG.x, rewardedBG.y = display.contentCenterX, 220

    rewardedLabel.x = display.contentCenterX
    rewardedLabel.y = 220

    rReady.x = display.contentCenterX + 140
    rReady.y = 220
    setRed(rReady)

    showRewardedButton.x = display.contentCenterX
    showRewardedButton.y = rewardedLabel.y + 40
end

-- initial layout
layoutDisplayObjects(system.orientation)
