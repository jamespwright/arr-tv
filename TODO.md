# arr-tv to-do

Jim's feedback from using the app on the TV. Tick items off (or delete them) as they're done.

## Bugs

- [x] **Going back up to the tab bar always lands on Settings.** It should land on the tab you're on (e.g. Downloads).
- [x] **Deleting a show sends you back to the Search tab.** It should stay on the Movies or TV Shows grid, with focus on a nearby poster.
  Likely cause: the deleted poster had focus, so focus falls to the tab bar, and tabs switch on focus.

## Performance

- [x] **Switching tabs is a little laggy**, especially Search → Downloads → Movies → TV Shows → Settings.
  Tabs you pass over are no longer drawn. Each poster grid still takes about 0.1s to appear when you stop on it.
- [x] **Scrolling the big Movies and TV Shows grids is a little laggy.**
  Fixed with the release build and 500px posters from the server. Janky frames went from 10% to 3%.

## Features

- [x] **Choose seasons when adding a show.** The add page should let you pick which seasons to monitor and download.
  Done: season chips on the add page (every season except specials to start with). New seasons are always monitored.
- [ ] **Popular titles on the Search tab.** Before you type anything, show the most popular released movies or TV shows
  (probably from IMDb) as poster tiles, the same as the Movies and TV Shows tabs.
  - Year filters (e.g. this year, last year).
  - The chosen filters are saved, so they're still set next time the app opens.
  - **On hold (25 Oct 2026), Jim's call.** IMDb has no free API and blocks apps from reading its charts. Radarr's metadata
    server only has a 20-movie TMDB list (no TV, no year filter). It needs a TMDB API key (or a Trakt client ID) from Jim.
- [x] **Search within the Movies and TV Shows tabs**, to find something in the library quickly. The library is big.
  Done: a search button above each grid (one press up from the posters) filters it as you type.
