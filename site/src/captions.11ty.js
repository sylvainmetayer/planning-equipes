// One WebVTT track per film: the videos are silent, and the track says so for the
// players that expect captions; the transcript below the video is the real alternative.
import copyOf from './_data/copy.js';

const copy = copyOf();
const clock = (seconds) => `00:${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}.000`;

export default class {
  data() {
    const tracks = ['fr', 'en'].flatMap((lang) =>
      copy[lang].accueil.images.films.map((f) => ({ lang, id: f.id, seconds: f.secondes, text: copy[lang].accueil.images.filmsLabels.sansSon })),
    );
    return {
      pagination: { data: 'tracks', size: 1, alias: 't' },
      tracks,
      permalink: (data) => `/videos/${data.t.lang}/${data.t.id}.vtt`,
      eleventyExcludeFromCollections: true,
    };
  }

  render({ t }) {
    return `WEBVTT\n\n00:00:00.000 --> ${clock(t.seconds)}\n${t.text}\n`;
  }
}
