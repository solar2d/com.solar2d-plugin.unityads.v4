-- UnityAds plugin

local Library = require "CoronaLibrary"

-- Create library
local lib = Library:new{ name="plugin.unityads.v4", publisherId="com.solar2d", version=1 }

-------------------------------------------------------------------------------
-- BEGIN
-------------------------------------------------------------------------------


local function showWarning(functionName)
    print( functionName .. " WARNING: The UnityAds plugin is only supported on Android & iOS devices. Please build for device")
end

function lib.init()
    showWarning("unityads.init()")
end

function lib.isLoaded()
    showWarning("unityads.isLoaded()")
end

function lib.load()
    showWarning("unityads.load()")
end

function lib.show()
    showWarning("unityads.show()")
end

function lib.setHasUserConsent()
    showWarning("unityads.setHasUserConsent()")
end

function lib.setPersonalizedAds()
    showWarning("unityads.setPersonalizedAds()")
end

function lib.setPrivacyMode()
    showWarning("unityads.setPrivacyMode()")
end

-------------------------------------------------------------------------------
-- END
-------------------------------------------------------------------------------

-- Return an instance
return lib
