# Brand assets

The app name is **똑똑 자가검침 AI**. The project name is **gas-self-meter-ai**.

The launcher artwork combines a household meter, a coral confirmation mark and two small knocking marks. Deep teal provides contrast with the warm meter body. There are no provider logos or tiny text that becomes illegible on a launcher.

The original raster artwork is in `docs/images/app-icon.png` and its identical packaged copy is in `app/src/main/res/drawable-nodpi/app_icon.png`. Android adaptive masks are configured in `mipmap-anydpi-v26/ic_launcher.xml`. A separate native vector outline in `drawable/ic_meter.xml` supports monochrome launcher themes and notifications. The image itself does not contain a baked-in rounded outer mask.

Generation mode was the built-in image-generation tool. No external API key was used. The generated original was copied into the project and preserved without raster edits.

Prompt used

> Create a final Android launcher icon for a Korean household gas meter estimation app named 똑똑 자가검침 AI. Square 1024 or larger, opaque deep teal full-bleed background. A friendly compact cream-white household gas meter with rounded corners, a dark teal display with three chunky light digit blocks, and a warm coral check at the lower right integrated into the design. Two short coral curved knocking marks above the meter. Polished simple vector-like illustration with subtle depth. No cartoon face. Centered symbol in the middle 62 percent for adaptive icon mask safety. No text, letters, tiny numbers, flames, pipes, borders, outer rounded square or watermarks.

UI colors

| Use | Color |
| --- | --- |
| Main actions | `#006C67` |
| Meter card | `#053F49` |
| Confirmation accent | `#FF845F` |
| Page | `#F7F8F4` |
| Text | `#192F32` |
| Secondary text | `#526968` |

Repository screenshots are captured from the actual Android app using synthetic demo data. They are not AI-generated interface mockups and contain no customer account information.
