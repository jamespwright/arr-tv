# arr-tv to-do

Jim's feedback from using the app on the TV. Tick items off (or delete them) as they're done.

## Bugs

- [x] **Going back up to the tab bar always lands on Settings.** It should land on the tab you're on (e.g. Downloads).
- [x] **Deleting a show sends you back to the Search tab.** It should stay on the Movies or TV Shows grid, with focus on a nearby poster.
  Likely cause: the deleted poster had focus, so focus falls to the tab bar, and tabs switch on focus.

## Performance

- [ ] **Switching tabs is a little laggy**, especially Search → Downloads → Movies → TV Shows → Settings.
- [ ] **Scrolling the big Movies and TV Shows grids is a little laggy.**

## Features

- [ ] **Choose seasons when adding a show.** The add page should let you pick which seasons to monitor and download.
  Right now it adds and searches for every season, but you may have already seen the earlier ones.
- [ ] **Popular titles on the Search tab.** Before you type anything, show the most popular released movies or TV shows
  (probably from IMDb) as poster tiles, the same as the Movies and TV Shows tabs.
  - Year filters (e.g. this year, last year).
  - The chosen filters are saved, so they're still set next time the app opens.
- [ ] **Search within the Movies and TV Shows tabs**, to find something in the library quickly. The library is big.
