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
- **Swipe to curate:** right keeps a clip and keeps playing it, left passes and moves on; both teach My Mix
- **My Mix:** a feed ranked on your phone from what you like, finish, skip and pin
- **Feeds:** Trending, Explore, For You, Liked, Following, creator pages and tag pages
- **Search builder:** several tags at once, words to leave out, and a sort order, narrowed further
  from related tags
- **Saved searches** on their own tab, each showing how many clips are new since you last looked
- **Muted words** that hide clips everywhere; mute or hide a tag straight from the player
- **More like this** follows a clip's least common tags rather than its most common ones
- **Saved lists**, watch history and search history
- **Hide** clips, creators and tags, and manage them in Settings
- **Layouts:** grid, staggered grid (2 to 4 columns), or a list that plays in place
- **Themes:** System, Light, Dark, AMOLED, Midnight, Sunset and Forest
- **Video quality:** Auto, HD or SD
- **Hold to sort** a clip into a collection, and collections that carry on into more like them
- **Updates:** checks GitHub releases and offers new versions; you choose whether to install
- **Preloading:** the next few clips are fetched ahead (whole clips on Wi-Fi, just their starts on
  mobile data)
- **App lock**, sleep timer, and JSON export and import of your data
- **Says when RedGIFs changes its API** instead of showing empty feeds

History, lists and taste stay on your phone unless you export them.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The unit tests include UI tests that run the whole app under Robolectric against a fake RedGIFs
(`app/src/test/java/com/oak404/redcast/ui`), so they need no device or network.

Needs JDK 17 and Android SDK platform 37. The APK lands in `app/build/outputs/apk/release/`. It is
signed with the local debug key, so builds from different machines won't install over each other.

## Releasing

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then:

```bash
scripts/release.sh "What changed"
```

It builds and tests the APK, tags the commit, and publishes a GitHub release with the APK attached.
Installed copies find it through the releases API and offer it as an update. Android only installs
an update signed with the same key, so every release has to come from the same signing key.

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
