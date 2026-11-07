# Redcast

An unofficial, open-source Android client for RedGIFs, with Chromecast and autoscroll that keeps
going on the TV.

**18+ only.** Redcast shows adult content from RedGIFs. It isn't affiliated with or endorsed by
RedGIFs, and it hosts no content of its own. The API it uses is unofficial and may change or stop
working at any time.

## Features

- **Sign in** with your own RedGIFs account, or browse as a guest
- **Chromecast** with a continuous queue: clips play one after another, and the phone can be locked
- **Autoscroll** to the next clip, or loop the current one
- **Swipe to curate:** right keeps a clip, left passes on it, and both teach My Mix
- **My Mix:** a feed ranked on your phone from what you like, finish, skip and pin
- **Feeds:** Trending, Explore, For You, Liked, Following, creator pages and tag pages
- **Saved lists**, watch history and search history
- **Hide** clips, creators and tags, and manage them in Settings
- **Layouts:** grid, staggered grid (2 to 4 columns), or a list that plays in place
- **Themes:** System, Light, Dark, AMOLED, Midnight, Sunset and Forest
- **Video quality:** Auto, HD or SD
- **App lock**, sleep timer, and JSON export of your data

History, lists and taste stay on your phone unless you export them.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Needs JDK 17 and Android SDK platform 37. The APK lands in `app/build/outputs/apk/release/`. It is
signed with the local debug key, so builds from different machines won't install over each other.

## How it works

- **Guest browsing:** `/v2/auth/temporary` tokens, cached because RedGIFs limits how often they can
  be requested.
- **Sign-in:** the real site runs in a WebView because login sits behind a Cloudflare captcha. A
  script limited to `www.redgifs.com` reads the bearer token the site attaches to its own API calls.
  The app never handles the password, and an off-screen WebView renews the token from the site's
  session cookie.
- **Phone autoscroll:** the feed is one ExoPlayer playlist, and the pager follows the player.
- **Cast:** the media host refuses Chromecast's default media receiver, so `CastRelay` serves clips
  from the phone over the local network, behind a random token and limited to the media host.
  `CastController` keeps a short window queued, tops it up as clips finish, and loops a finished
  feed. `CastKeepAliveService`, a foreground service, keeps that going with the phone locked.

## License

[MIT](LICENSE)
