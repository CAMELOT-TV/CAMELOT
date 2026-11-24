# Backgrounds

Every picture in this folder becomes a home screen background. They crossfade one after another with a slow zoom, in file-name order.

- **Formats:** `.jpg`, `.jpeg`, `.png`, `.webp`
- **Best shape:** landscape 16:9, for example 1920×1080 or bigger. Other shapes are cropped to fill the screen.
- **Size:** with Pillow installed (`pip install Pillow`), the build crops and resizes every picture to 1920×1080. Without it, pictures are copied as they are, so keep them under a few MB each.
- **Order:** name them `01-....jpg`, `02-....jpg` and so on to choose the order.
- **How many:** as many as you like, but each one adds to the app's size. Somewhere between 5 and 20 is plenty.
- **Dark pictures work best,** because the white and gold text sits on top. `backgroundDim` in `config/camelot.json` controls the shade over them.

Two pictures come with Camelot. Delete either one if you don't want it:

- `city-dusk.jpg`: Singapore at dusk, photo by [Wengang Zhai](https://unsplash.com/@wgzhai) on [Unsplash](https://unsplash.com/photos/aerial-view-of-city-buildings-during-sunset-Q7UZxzdiCHY), used under the [Unsplash License](https://unsplash.com/license) (free to use; this licence, not MIT, applies to that photo).
- `night-sky.jpg`: an original picture made for Camelot, MIT-licensed like the rest of the project.

Only add pictures you have the rights to use, such as your own photos or properly licensed images. That matters most if you share your fork.
