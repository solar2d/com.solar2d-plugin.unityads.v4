//
//  UnityAdsPlugin.h
//  UnityAds Plugin
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#ifndef _UnityAdsPlugin_H_
#define _UnityAdsPlugin_H_

#import "CoronaLua.h"
#import "CoronaMacros.h"

// This corresponds to the name of the library, e.g. [Lua] require "plugin.library"
// where the '.' is replaced with '_'
CORONA_EXPORT int luaopen_plugin_unityads_v4( lua_State *L );

#endif // _UnityAdsPlugin_H_
