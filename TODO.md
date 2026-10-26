# arr-tv to-do

Jim's feedback from using the app on the TV. Tick items off (or delete them) as they're done.

## Bugs

- [x] **Going back up to the tab bar always lands on Settings.** It should land on the tab you're on (e.g. Downloads).
- [x] **Deleting a show sends you back to the Search tab.** It should stay on the Movies or TV Shows grid, with focus on a nearby poster.
  Likely cause: the deleted poster had focus, so focus falls to the tab bar, and tabs switch on focus.

- [x] **The episodes on a show's page are off the screen, and the page looks bunched up vertically.**
  Done: show pages now have two panes. The show, its actions and quality are on the left. Seasons and episodes are on the right,
  and the episode list scrolls on its own. The season chips start on the selected season.

## Performance

- [x] **Switching tabs is a little laggy**, especially Search → Downloads → Movies → TV Shows → Settings.
  Tabs you pass over are no longer drawn. Each poster grid still takes about 0.1s to appear when you stop on it.
- [x] **Scrolling the big Movies and TV Shows grids is a little laggy.**
  Fixed with the release build and 500px posters from the server. Janky frames went from 10% to 3%.

## Features

- [x] **Choose seasons when adding a show.** The add page should let you pick which seasons to monitor and download.
  Done: season chips on the add page (none selected to start with). New seasons are always monitored.
- [x] **Popular titles on the Search tab.** Before you type anything, show the most popular released movies or TV shows
  as poster tiles, the same as the Movies and TV Shows tabs.
  Done: TMDB's discover API, in popularity order (movies only once out on digital or Blu-ray; no news, soaps or talk
  shows). Filters: any year / this year / last year, and any rating / 7+ / 8+. The filters and Movies/TV choice
  are saved. The search box and filters scroll away with the posters so more of the grid fits on screen. Titles already in Radarr
  or Sonarr say "In library". The key is in Settings, or `arr.tmdbKey` in `local.properties`.
- [x] **Search within the Movies and TV Shows tabs**, to find something in the library quickly. The library is big.
  Done: a search button above each grid (one press up from the posters) filters it as you type.
- [x] **Sort Movies and TV Shows by when something was last added**, like Plex.
  Done: newest first. Shows go by their newest episode file ("Last Episode Date Added" in Plex), movies by their file's date.
  Anything with no file yet goes by when it was added to Radarr or Sonarr, so new requests show up at the front.
- [x] **Show which movies are out on streaming**, so it's clear which ones will actually download.
  Done: an "On streaming / All releases" filter on the Search tab (saved) hides or shows the ones not out yet, for both
  popular titles and search results. The release, year and rating filters are dropdowns (`ui/Dropdown.kt`), so they
  all fit on the "Popular movies" row. The add and detail pages say "On streaming since <date>",
  "In cinemas since <date> · streaming from <date>", and so on. (Poster badges were tried and taken out.) "Streaming" means a digital or physical release anywhere, from TMDB or Radarr,
  or 90 days in cinemas with no date (as Radarr assumes).
- [x] **Watch trailer button** on the add and detail pages, for movies and shows, that plays inside the app.
  Done: YouTube's embedded player in a full-screen WebView. TMDB's official
  trailers first, then Radarr's, then a YouTube search for "[title] [year] trailer". If YouTube won't let one play
  outside its app, the next is tried. OK pauses, left/right skip 10 seconds, Back closes. Captions are off.
- [x] **Arrange by, genre and streaming platform dropdowns** on the Search tab's popular titles, for movies and TV.
  Done: dropdowns on the same row as the year and rating ones (saved), with no heading so they all fit. Arrange by: Most popular / Top rated / Newest / Title A–Z. It only
  reorders the list (the most popular titles matching the filters), so it never changes what's in it. Genres are TMDB's, kept
  separately for movies and TV. Platforms are subscription services (Netflix, Prime Video, Disney+, Apple TV+, HBO Max,
  Paramount+, Stan, Binge, Hulu, Peacock, Crunchyroll). TMDB only knows what's on a service per country, so each one is
  checked in the US, UK, Australia and Canada (where it exists there) and the lists are merged.
- [x] **Year and rating filters as "or later" / "or higher".** Year: any year, or 2026+ back to 2016+ (that year or
  later, ten years back from this one). Rating: any rating, or ★ 1+ to ★ 10. The year is saved as the year itself,
  so 2024+ stays 2024+ next year.
- [x] **Popular shows with few TMDB votes were missing** (The Secret Lives of Mormon Wives, on Disney+ with Reality
  chosen). The lists asked for at least 50 votes. Now it's 10, or 50 only when filtering by rating.
