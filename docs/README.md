# VeloSpot - Bike Parking Finder for Trier

## Overview

This directory contains the GitHub Pages website for VeloSpot. The site is automatically deployed from the `/docs` folder on the `main` branch.

## Website Features

✨ **Modern Design**
- Responsive layout (mobile, tablet, desktop)
- Dark-themed navigation with gradient accents
- Card-based feature showcase
- Call-to-action sections

🎨 **Content Sections**
- Hero banner with project introduction
- Features overview with icons
- Technology stack showcase
- Product highlights for favorites, my location, dark mode, and SQLite caching
- Getting started guide
- Footer with social links

## File Structure

```
docs/
└── index.html    # Main website (all-in-one file)
```

## Customization

To modify the website, edit `docs/index.html`:

### Colors
Update the CSS variables at the top of the `<style>` section:
```css
:root {
    --primary-color: #0A2A66;      /* Main brand color */
    --secondary-color: #7DB3FF;    /* Accent color */
    --text-dark: #1a1a1a;          /* Dark text */
    --text-light: #555;            /* Light text */
    --bg-light: #f5f5f5;          /* Background */
}
```

### Content
Update section titles, descriptions, and links as needed.

Current website copy highlights:
- Favorites and direct navigation shortcuts
- Current-location tools and live location marker
- Dark mode toggle in the app menu
- Room / SQLite local caching
- OpenStreetMap attribution in the footer

## Deployment

The site is automatically deployed when you:
1. Push changes to `main` branch
2. GitHub automatically rebuilds and deploys

The site will be available at: `https://drzeeb.github.io/VeloSpot/`

## Performance Tips

- Images are optimized with CSS gradients (no heavy graphics)
- Minimal JavaScript (pure CSS animations)
- Fast load times with inline CSS
- Mobile-first responsive design

## Browser Compatibility

✅ Chrome 90+
✅ Firefox 88+
✅ Safari 14+
✅ Edge 90+
✅ Mobile browsers

## Support

For issues or improvements to the website, open an issue in the main VeloSpot repository.

---

Happy coding! 🚲

