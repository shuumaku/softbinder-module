# softbinder-module

LSPosed/Xposed module (scope: System Framework) that binds actions to the unclaimed F1-F4 softkeys of Kyocera phones with the softkey bar guide. Tested on a [KY-42C](https://garahowiki.com/phones:kyocera_digno_keitai_4:start).

Reads `/data/system/softbinder.json`, edited by [softbinder-config](https://github.com/shuumaku/softbinder-config):

Example of the configuration file:

```json
{
  "debug": false,
  "binds": [
    {
      "index": 1,
      "app": "com.weawow",
      "key": "F3",
      "label": "Refresh",
      "action": "swipe",
      "param": "down"
    }
  ]
}
```

Actions: `launch`, `intent`, `broadcast`, `swipe`, `key`. `"debug": true` logs to `/data/system/softbinder.log`.

A bind can be limited with `"window"` (`base` by default, `dialog`, `ime` for keyboard showing, or `any`) and `"activity"` (the end of the window's activity name). When several binds fit a key, the most specific one wins, so one key can change with the window.

Build: `./build.sh`

GPL-3.0, see LICENSE.
