import { animate, inView, stagger } from 'motion';

var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// Eyebrow subtitle typewriter: driven directly by motion's animate() rather than a CSS
// @keyframes width animation — a hardcoded `ch` count doesn't reliably match this font's actual
// rendered width (measured ~10% short), and a CSS custom property set from JS didn't reliably
// feed an already-scheduled keyframe's `to` value either. Animating between two JS-measured px
// values (0 and the element's real scrollWidth) sidesteps both.
var eyebrowSub = document.querySelector('.eyebrow-sub');
if (eyebrowSub) {
  if (reduceMotion) {
    eyebrowSub.style.width = 'auto';
  } else {
    var eyebrowTargetWidth = eyebrowSub.scrollWidth;
    animate(eyebrowSub, { width: [0, eyebrowTargetWidth] }, { duration: 1.1, delay: 0.5, easing: 'linear' });
  }
}

// Hero: interrogate the test. Each line is a question a green run doesn't actually answer —
// the explanation and payoff underneath are where Vortex enters the story.
var cycleEl = document.querySelector('[data-role="cycle-text"]');
var cyclePhrases = [
  'Did the service break — or did your load generator?',
  '1,000 virtual users. Based on what?',
  '500 req/s. Why 500?',
  'Everything passed. Did you actually stress anything?',
  'Your p95 is 180ms. Cool. Is that good?',
  'You found the breakpoint. Whose breakpoint?',
  'CPU hit 100%. And then what happened?',
  'Was that capacity — or just the biggest number you tried?',
  'Congratulations. k6 exited 0. What did we learn?',
  'The test passed. Production disagrees.',
  'Your workload mix — real traffic, or a guess?'
];
if (cycleEl && !reduceMotion) {
  var cycleIndex = 0;
  setInterval(function () {
    cycleEl.classList.add('cycle-out');
    setTimeout(function () {
      cycleIndex = (cycleIndex + 1) % cyclePhrases.length;
      cycleEl.textContent = cyclePhrases[cycleIndex];
      cycleEl.classList.remove('cycle-out');
    }, 300);
  }, 2600);
}

// Interrogation stage: two sketchnote figures step through the escalating exchange once the
// section scrolls into view, each line landing in a speech bubble above the speaker's head.
// Under reduced motion the stage stays hidden (see CSS) and .interro-transcript — a plain,
// always-visible rendering of the same exchange — takes over instead, so nothing is lost.
var interroFinale = document.querySelector('[data-role="interro-finale"]');
if (reduceMotion && interroFinale) {
  interroFinale.classList.add('in-view');
}

var interroStage = document.querySelector('[data-role="interro-stage"]');
if (interroStage && !reduceMotion) {
  var leadBubble = interroStage.querySelector('[data-role="lead-bubble"]');
  var engBubble = interroStage.querySelector('[data-role="eng-bubble"]');
  var leadText = interroStage.querySelector('[data-role="lead-text"]');
  var engText = interroStage.querySelector('[data-role="eng-text"]');
  var leadFigure = interroStage.querySelector('.interro-actor-lead .interro-figure');
  var engFigure = interroStage.querySelector('.interro-actor-eng .interro-figure');
  var stageStatus = interroStage.querySelector('[data-role="stage-status"]');
  var activeSpeaker = null;

  function gestureFigure(speaker) {
    var figure = speaker === 'lead' ? leadFigure : engFigure;
    figure.classList.remove('is-talking');
    void figure.offsetWidth;
    figure.classList.add('is-talking');
  }

  var interroSteps = [
    { s: 'lead', t: 'Yo!' },
    { s: 'lead', t: 'Can it handle production?' },
    { s: 'eng', t: 'Probably.' },
    { s: 'lead', t: 'What did you test?' },
    { s: 'eng', t: '<span class="interro-num">1,000 req/s</span>.' },
    { s: 'lead', t: 'Why 1,000?' },
    { s: 'eng', hesitate: true },
    { s: 'eng', t: 'Nice round number.' },
    { s: 'lead', t: 'For how long?' },
    { s: 'eng', t: '<span class="interro-num">10 minutes</span>.' },
    { s: 'lead', t: 'Why ten?' },
    { s: 'eng', hesitate: true },
    { s: 'eng', t: 'It felt sufficient.' },
    { status: true },
    // keep: true — the closing beat freezes as a complete scene (status pill, both bubbles)
    // instead of the usual one-speaker-at-a-time hiding, since the finale reads off of it.
    { s: 'lead', t: 'So we’re good for production?', keep: true },
    { s: 'eng', t: 'Probably.', keep: true },
    { finale: true }
  ];

  function hideInterroBubbles() {
    leadBubble.classList.remove('is-visible');
    engBubble.classList.remove('is-visible');
  }

  function showInterroBubble(speaker, html, hesitate, keep) {
    var bubble = speaker === 'lead' ? leadBubble : engBubble;
    var textEl = speaker === 'lead' ? leadText : engText;
    var otherBubble = speaker === 'lead' ? engBubble : leadBubble;
    if (!keep) {
      if (activeSpeaker && activeSpeaker !== speaker) {
        otherBubble.classList.remove('is-visible');
      }
      stageStatus.classList.remove('is-visible');
    }
    textEl.innerHTML = hesitate
      ? '<span class="interro-dot"></span><span class="interro-dot"></span><span class="interro-dot"></span>'
      : html;
    bubble.classList.add('is-visible');
    activeSpeaker = speaker;
    if (!hesitate) { gestureFigure(speaker); }
  }

  // A generation token guards against two step chains ever running at once — e.g. if inView
  // somehow re-fired, or a stale timeout survived a dev-server hot reload — either of which
  // would interleave two chains and make the exchange look like it's skipping around.
  var interroIndex = 0;
  var interroGeneration = 0;

  function scheduleInterroStep(gen, delay) {
    setTimeout(function () {
      if (gen !== interroGeneration) return;
      runInterroStep(gen);
    }, delay);
  }

  function runInterroStep(gen) {
    if (gen !== interroGeneration) return;
    if (interroIndex >= interroSteps.length) return;
    var step = interroSteps[interroIndex];
    interroIndex++;

    if (step.status) {
      hideInterroBubbles();
      stageStatus.classList.add('is-visible');
      scheduleInterroStep(gen, 1700);
      return;
    }
    if (step.finale) {
      // Leaves the status pill and both bubbles exactly as the last two (keep: true) steps left
      // them — the finale reads as the payoff sitting below a completed scene, not a fresh one.
      if (interroFinale) { interroFinale.classList.add('in-view'); }
      if (interroReplay) { interroReplay.classList.add('is-visible'); }
      return;
    }
    showInterroBubble(step.s, step.t, step.hesitate, step.keep);
    scheduleInterroStep(gen, step.hesitate ? 900 : 1500);
  }

  // Plays once, on scroll into view. It ends by staying on the finale rather than looping — a
  // 20-second exchange repeating every few seconds wore out its welcome — and reveals a replay
  // button so anyone who wants to watch it again can, on their own terms.
  function startInterroSequence() {
    interroGeneration++;
    var gen = interroGeneration;
    interroIndex = 0;
    activeSpeaker = null;
    hideInterroBubbles();
    stageStatus.classList.remove('is-visible');
    if (interroFinale) { interroFinale.classList.remove('in-view'); }
    if (interroReplay) { interroReplay.classList.remove('is-visible'); }
    scheduleInterroStep(gen, 200);
  }

  var interroReplay = document.querySelector('[data-role="interro-replay"]');
  if (interroReplay) {
    interroReplay.addEventListener('click', startInterroSequence);
  }

  var interroStarted = false;
  inView(interroStage, function () {
    if (interroStarted) return;
    interroStarted = true;
    startInterroSequence();
  }, { amount: 0.4 });
}

// Problem section: reveal the follow-up line once the statement scrolls into view.
var statement = document.querySelector('.statement');
if (statement) {
  if (reduceMotion) {
    statement.classList.add('in-view');
  } else {
    inView(statement, function () {
      statement.classList.add('in-view');
    }, { amount: 0.5 });
  }
}

// How it works: a hand-drawn figure walks the length of the station-map once it scrolls into view,
// each station and its connector lighting up roughly as the figure passes it, arriving at Evidence
// to reveal "You. About to stop guessing." — a single timed sequence, not scroll-linked, because
// the whole row lives inside one min-height:100vh section: there's no scroll distance across it to
// drive an animation with, the way a tall page would give a vertical scroll-linked reveal.
// Stations are read as a flat, document-order list (not a sibling walk) because three of them sit
// nested inside .station-map-canvas — querySelectorAll already returns document order regardless
// of that nesting.
var stationMap = document.querySelector('.station-map');
var stations = Array.prototype.slice.call(document.querySelectorAll('.station'));
var stationConnectors = Array.prototype.slice.call(document.querySelectorAll('.station-connector'));
var stationCanvas = document.querySelector('.station-map-canvas');
var walkFigure = document.querySelector('.station-map-walk-figure');
var walkCaption = document.querySelector('.station-map-walk-caption');
var firstCanvasStationIndex = stationCanvas ? stations.findIndex(function (el) { return stationCanvas.contains(el); }) : -1;
// Below the row's own breakpoint (see styles.css), .station-map-row stacks into a column taller
// than any viewport — inView(stationMap, {amount: 0.98}) below needs the *whole* row visible at
// once to time the walk against, which a tall stack can never satisfy, leaving every station stuck
// invisible. The walk needs real horizontal distance in the first place (.station-map-walk is
// display:none there too), so mobile gets the simpler per-element reveal every other scroll-driven
// section on this page already uses instead of trying to force the walk into a column.
var isStackedLayout = window.matchMedia('(max-width: 860px)').matches;

if (stationMap && stations.length) {
  var lightUpAll = function () {
    stations.forEach(function (el) { el.classList.add('active'); });
    stationConnectors.forEach(function (el) { el.classList.add('active'); });
    if (stationCanvas) stationCanvas.classList.add('active');
    if (walkCaption) walkCaption.classList.add('is-visible');
  };

  if (reduceMotion) {
    lightUpAll();
  } else if (isStackedLayout) {
    // No walking figure on mobile (.station-map-walk is display:none there — see styles.css) — just
    // each station, connector, and the canvas revealing individually as it's scrolled into view.
    stations.forEach(function (el) {
      inView(el, function () { el.classList.add('active'); }, { amount: 0.4, margin: '0px 0px -10% 0px' });
    });
    stationConnectors.forEach(function (el) {
      inView(el, function () { el.classList.add('active'); }, { amount: 0.4, margin: '0px 0px -10% 0px' });
    });
    if (stationCanvas) {
      inView(stationCanvas, function () { stationCanvas.classList.add('active'); }, { amount: 0.2, margin: '0px 0px -10% 0px' });
    }
  } else {
    var walked = false;

    var startWalk = function () {
      if (walked) return;
      walked = true;

      var totalDuration = 4.8;
      var stationDelay = stagger(totalDuration / Math.max(stations.length - 1, 1));
      var mapRect = stationMap.getBoundingClientRect();

      if (walkFigure) {
        var targets = stations.map(function (el) {
          var icon = el.querySelector('.station-icon');
          var r = icon.getBoundingClientRect();
          return (r.left + r.width / 2) - mapRect.left - walkFigure.clientWidth / 2;
        });
        walkFigure.style.opacity = '1';
        animate(walkFigure, { x: targets }, { duration: totalDuration, easing: 'ease-in-out' });

        // The caption lands under wherever the figure actually stops (its final x varies with
        // viewport width), not at a fixed corner — pinning it to a static position is what caused
        // it to overlap the figure when the walk ended near the right edge.
        if (walkCaption) {
          walkCaption.style.left = (targets[targets.length - 1] + walkFigure.clientWidth / 2) + 'px';
        }
      }

      stations.forEach(function (el, index) {
        setTimeout(function () {
          el.classList.add('active');
          var connector = stationConnectors[index - 1];
          if (connector) connector.classList.add('active');
          if (stationCanvas && index === firstCanvasStationIndex) stationCanvas.classList.add('active');
        }, stationDelay(index, stations.length) * 1000);
      });

      setTimeout(function () {
        if (walkCaption) walkCaption.classList.add('is-visible');
      }, totalDuration * 1000);
    };

    // Wait for the reader to actually be looking at a settled screen, not a mid-scroll one: the
    // section has to be (almost) fully in view AND the scroll-snap transition has to have actually
    // finished. `scrollend` reports that directly; browsers without it (older Safari) fall back to
    // a fixed delay comfortably longer than the page's own snap transition.
    var armed = false;
    var settleFallback = null;

    var onScrollEnd = function () {
      if (settleFallback) clearTimeout(settleFallback);
      window.removeEventListener('scrollend', onScrollEnd);
      startWalk();
    };

    inView(stationMap, function () {
      if (armed) return;
      armed = true;
      if ('onscrollend' in window) {
        window.addEventListener('scrollend', onScrollEnd);
        settleFallback = setTimeout(onScrollEnd, 1600);
      } else {
        settleFallback = setTimeout(startWalk, 900);
      }
    }, { amount: 0.98 });
  }
}

// Concept diagram, showcase cards, architecture diagram: same reveal-on-scroll idiom as the
// statement and flow sections above, generalized for anything marked data-reveal. Showcase cards
// (and the diagram's input/output pill lists) additionally cascade in via a small stagger, rather
// than reinforcing the illusion that everything on the page has arrived at once.
var revealEls = document.querySelectorAll('[data-reveal]:not(.statement)');
var showcaseItems = document.querySelectorAll('.showcase-item');
var staggerDelay = stagger(0.1);

function staggerDiagramLists(diagram) {
  var items = diagram.querySelectorAll('.diagram-inputs li, .diagram-outputs li');
  items.forEach(function (li, i) {
    li.style.transitionDelay = staggerDelay(i, items.length) + 's';
  });
}

if (revealEls.length) {
  if (reduceMotion) {
    revealEls.forEach(function (el) { el.classList.add('in-view'); });
  } else {
    revealEls.forEach(function (el) {
      var showcaseIndex = Array.prototype.indexOf.call(showcaseItems, el);
      if (showcaseIndex !== -1) {
        el.style.transitionDelay = staggerDelay(showcaseIndex, showcaseItems.length) + 's';
      }
      if (el.classList.contains('vortex-diagram')) {
        staggerDiagramLists(el);
      }
      inView(el, function () {
        el.classList.add('in-view');
      }, { amount: 0.2 });
    });
  }
}

// Subtle press feedback on buttons — the only purely-decorative addition here, kept cheap
// (a 0.1s scale tween) and skipped entirely under reduced motion.
var buttons = document.querySelectorAll('.btn');
if (buttons.length && !reduceMotion) {
  buttons.forEach(function (btn) {
    var press = function () { animate(btn, { scale: 0.97 }, { duration: 0.1 }); };
    var release = function () { animate(btn, { scale: 1 }, { duration: 0.1 }); };
    btn.addEventListener('pointerdown', press);
    btn.addEventListener('pointerup', release);
    btn.addEventListener('pointerleave', release);
  });
}

// Architecture diagram + concept-diagram inputs: each rests at a small hand-placed tilt (set in
// HTML via --tilt / data-tilt) for a sketchnote feel. On hover it "picks up" — straightens and
// lifts with a spring, like plucking a sticky note off a whiteboard — then springs back to its
// tilt on release.
var archNodes = document.querySelectorAll('.arch-node, .diagram-inputs li, .station--input .station-card');
if (archNodes.length && !reduceMotion) {
  archNodes.forEach(function (node) {
    var tilt = parseFloat(node.dataset.tilt) || 0;
    var pickUp = function () {
      animate(node, { rotate: 0, y: -4 }, { type: 'spring', stiffness: 320, damping: 16 });
    };
    var putDown = function () {
      animate(node, { rotate: tilt, y: 0 }, { type: 'spring', stiffness: 320, damping: 16 });
    };
    node.addEventListener('pointerenter', pickUp);
    node.addEventListener('pointerleave', putDown);
  });
}

// Showcase lightbox: clicking a screenshot morphs it — FLIP-style, from its exact on-page
// position and size — into a large, navigable view, and morphs back to that same spot on close.
// The morph itself is skipped under reduced motion; the viewer (open/close/prev/next) still works,
// just without the transform tween.
(function initLightbox() {
  var items = Array.prototype.slice.call(document.querySelectorAll('.showcase-item'));
  var frames = Array.prototype.slice.call(document.querySelectorAll('.window-frame'));
  if (!items.length || items.length !== frames.length) return;

  var overlay = document.createElement('div');
  overlay.className = 'lightbox';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', 'Screenshot viewer');
  overlay.innerHTML =
    '<button type="button" class="lightbox-close" aria-label="Close">&times;</button>' +
    '<button type="button" class="lightbox-prev" aria-label="Previous screenshot">&lsaquo;</button>' +
    '<button type="button" class="lightbox-next" aria-label="Next screenshot">&rsaquo;</button>' +
    '<figure class="lightbox-figure">' +
    '<img class="lightbox-img" alt="">' +
    '<figcaption><strong></strong><p></p></figcaption>' +
    '</figure>' +
    '<div class="lightbox-dots"></div>';
  document.body.appendChild(overlay);

  var imgEl = overlay.querySelector('.lightbox-img');
  var titleEl = overlay.querySelector('figcaption strong');
  var descEl = overlay.querySelector('figcaption p');
  var dotsEl = overlay.querySelector('.lightbox-dots');
  var closeBtn = overlay.querySelector('.lightbox-close');
  var prevBtn = overlay.querySelector('.lightbox-prev');
  var nextBtn = overlay.querySelector('.lightbox-next');

  items.forEach(function () {
    var dot = document.createElement('span');
    dot.className = 'lightbox-dot';
    dotsEl.appendChild(dot);
  });
  var dots = Array.prototype.slice.call(dotsEl.querySelectorAll('.lightbox-dot'));

  var currentIndex = -1;
  var lastTrigger = null;
  var springTransition = { type: 'spring', stiffness: 420, damping: 38, mass: 0.5, restDelta: 0.5 };

  function dataFor(index) {
    var img = frames[index].querySelector('.theme-shot');
    return {
      img: img,
      src: img.currentSrc || img.src,
      alt: img.alt,
      title: items[index].querySelector('figcaption strong').textContent,
      desc: items[index].querySelector('figcaption p').textContent
    };
  }

  function renderContent(index) {
    var data = dataFor(index);
    imgEl.src = data.src;
    imgEl.alt = data.alt;
    titleEl.textContent = data.title;
    descEl.textContent = data.desc;
    dots.forEach(function (dot, i) { dot.classList.toggle('is-active', i === index); });
    currentIndex = index;
  }

  // Reads where the enlarged image actually ended up (post-layout) versus where the clicked
  // thumbnail sits right now, and returns the transform delta that makes them coincide.
  function flipDeltaFrom(sourceImg) {
    var from = sourceImg.getBoundingClientRect();
    var to = imgEl.getBoundingClientRect();
    return {
      x: (from.left + from.width / 2) - (to.left + to.width / 2),
      y: (from.top + from.height / 2) - (to.top + to.height / 2),
      scale: from.width / to.width
    };
  }

  function open(index, triggerFrame) {
    lastTrigger = triggerFrame;
    renderContent(index);
    overlay.classList.add('is-open');
    document.body.style.overflow = 'hidden';
    closeBtn.focus();

    if (reduceMotion) {
      overlay.classList.add('is-visible');
      return;
    }

    var delta = flipDeltaFrom(triggerFrame.querySelector('.theme-shot'));
    requestAnimationFrame(function () {
      overlay.classList.add('is-visible');
      animate(
        imgEl,
        { x: [delta.x, 0], y: [delta.y, 0], scale: [delta.scale, 1] },
        springTransition
      );
    });
  }

  function close() {
    var triggerImg = lastTrigger && lastTrigger.querySelector('.theme-shot');
    var restoreFocus = lastTrigger;

    function finish() {
      overlay.classList.remove('is-open', 'is-visible');
      document.body.style.overflow = '';
      imgEl.style.transform = '';
      if (restoreFocus) restoreFocus.focus();
    }

    if (reduceMotion || !triggerImg) {
      finish();
      return;
    }

    overlay.classList.remove('is-visible');
    var delta = flipDeltaFrom(triggerImg);
    animate(
      imgEl,
      { x: [0, delta.x], y: [0, delta.y], scale: [1, delta.scale] },
      springTransition
    ).then(finish);
  }

  function step(offset) {
    var nextIndex = (currentIndex + offset + items.length) % items.length;
    if (reduceMotion) {
      renderContent(nextIndex);
      return;
    }
    animate(imgEl, { opacity: [1, 0], x: [0, offset > 0 ? -16 : 16] }, { duration: 0.15 }).then(function () {
      renderContent(nextIndex);
      animate(imgEl, { opacity: [0, 1], x: [offset > 0 ? 16 : -16, 0] }, { duration: 0.2 });
    });
  }

  frames.forEach(function (frame, i) {
    frame.addEventListener('click', function () { open(i, frame); });
    frame.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        open(i, frame);
      }
    });
  });
  closeBtn.addEventListener('click', close);
  prevBtn.addEventListener('click', function () { step(-1); });
  nextBtn.addEventListener('click', function () { step(1); });
  overlay.addEventListener('click', function (event) {
    if (event.target === overlay) close();
  });
  document.addEventListener('keydown', function (event) {
    if (!overlay.classList.contains('is-open')) return;
    if (event.key === 'Escape') close();
    else if (event.key === 'ArrowLeft') step(-1);
    else if (event.key === 'ArrowRight') step(1);
  });
})();

// Vortex spiral: its stroke-dashoffset "flow" is a real repaint on every frame (unlike the
// GPU-composited transform animations elsewhere on the page), so unlike the one-shot reveals
// above, keep tracking visibility for its whole lifetime and pause the animation whenever the
// diagram scrolls off-screen — no point repainting a diagram nobody can see.
var spiral = document.querySelector('.diagram-spiral');
if (spiral && !reduceMotion && 'IntersectionObserver' in window) {
  var spiralObserver = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      spiral.classList.toggle('is-flowing', entry.isIntersecting);
    });
  }, { threshold: 0 });
  spiralObserver.observe(spiral);
}

// Background vortex: an actual particle whirlpool spiraling into the hero's focal point,
// gently reactive to the cursor. Cost scales with particle count, not viewport pixel area — the
// property the old blurred conic-gradient layer lacked (see styles.css .vortex-bg) — so this
// stays cheap on wide screens instead of getting worse on them. Only runs while the hero is in
// view (same reasoning as before: no point animating a canvas nobody can see), and is skipped
// entirely under reduced motion rather than just paused, since there's no static equivalent worth
// drawing once — the mask fade alone reads fine as a quiet backdrop.
(function initVortexCanvas() {
  var canvas = document.querySelector('.vortex-bg');
  var heroSection = document.querySelector('.hero');
  if (!canvas || !heroSection || reduceMotion || !canvas.getContext) return;

  var ctx = canvas.getContext('2d');
  var dpr = Math.min(window.devicePixelRatio || 1, 1.5);
  var width = 0, height = 0, originX = 0, originY = 0;
  var pointer = { x: 0, y: 0, active: false };
  var particles = [];
  var particleCount = 60;
  var running = false;
  var rafId = null;
  var accentRgb = '95, 214, 201';

  function readTheme() {
    var styles = getComputedStyle(document.documentElement);
    accentRgb = styles.getPropertyValue('--accent-rgb').trim() || accentRgb;
  }

  function resize() {
    width = heroSection.clientWidth;
    height = heroSection.clientHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    originX = width * 0.5;
    originY = height * 0.22;
  }

  function rand(min, max) { return min + Math.random() * (max - min); }

  function spawn(p, atEdge) {
    p.angle = rand(0, Math.PI * 2);
    p.radius = atEdge ? rand(260, 420) : rand(10, 420);
    p.spin = rand(0.35, 0.9);
    p.inward = rand(0.25, 0.55);
    p.size = rand(0.6, 2.1);
    p.alpha = rand(0.35, 0.85);
  }

  for (var i = 0; i < particleCount; i++) {
    var p = {};
    spawn(p, false);
    particles.push(p);
  }

  function frame() {
    // Fading via a translucent same-color fillRect (the original approach) never actually reaches
    // the page's true background — canvas alpha compositing asymptotes toward opaque but never quite
    // gets there, so the whole masked circle steady-states a few RGB units off from the surrounding
    // page background. Against near-black that small, uniform offset reads as a visibly different
    // shade over the whole vortex area, not just a trail behind each particle.
    // destination-out instead fades the canvas's own alpha down (toward fully transparent) rather
    // than tinting it toward an approximated background color. Untouched pixels are already fully
    // transparent — true page background showing through, exact match, no offset — and only pixels a
    // particle actually painted have any alpha to fade, so the trail decays to literally nothing
    // instead of to a near-miss color.
    ctx.globalCompositeOperation = 'destination-out';
    ctx.fillStyle = 'rgba(0, 0, 0, 0.08)';
    ctx.fillRect(0, 0, width, height);
    ctx.globalCompositeOperation = 'source-over';

    for (var i = 0; i < particles.length; i++) {
      var p = particles[i];
      p.angle += p.spin * 0.02;
      p.radius -= p.inward;
      if (p.radius < 8) spawn(p, true);

      var x = originX + Math.cos(p.angle) * p.radius;
      var y = originY + Math.sin(p.angle) * p.radius * 0.55;

      if (pointer.active) {
        var dx = pointer.x - x, dy = pointer.y - y;
        var dist = Math.sqrt(dx * dx + dy * dy) || 1;
        if (dist < 160) {
          x += (dx / dist) * (160 - dist) * 0.05;
          y += (dy / dist) * (160 - dist) * 0.05;
        }
      }

      ctx.beginPath();
      ctx.arc(x, y, p.size, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(' + accentRgb + ', ' + p.alpha + ')';
      ctx.fill();
    }

    if (running) rafId = requestAnimationFrame(frame);
  }

  function start() {
    if (running) return;
    running = true;
    rafId = requestAnimationFrame(frame);
  }

  function stop() {
    running = false;
    if (rafId) cancelAnimationFrame(rafId);
  }

  readTheme();
  resize();
  window.addEventListener('resize', resize);
  window.addEventListener('pointermove', function (event) {
    // Particle coordinates live in the canvas's own (hero-local) space, so pointer coordinates —
    // naturally viewport-relative — need to be translated by the hero's current scroll position.
    var rect = heroSection.getBoundingClientRect();
    pointer.x = event.clientX - rect.left;
    pointer.y = event.clientY - rect.top;
    pointer.active = true;
  });
  var colorScheme = window.matchMedia('(prefers-color-scheme: dark)');
  if (colorScheme.addEventListener) colorScheme.addEventListener('change', readTheme);

  if ('IntersectionObserver' in window) {
    var heroObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting && document.visibilityState === 'visible') start();
        else stop();
      });
    }, { threshold: 0 });
    heroObserver.observe(heroSection);
  } else {
    start();
  }

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') stop();
    else if (heroSection.getBoundingClientRect().bottom > 0) start();
  });
})();

// Hero scroll cue: nudges the reader past the fold, then gets out of the way once they've
// started scrolling on their own so it doesn't linger over the next section.
(function initScrollCue() {
  var heroSection = document.querySelector('.hero');
  var scrollCue = document.querySelector('[data-role="scroll-cue"]');
  if (!heroSection || !scrollCue) return;

  scrollCue.addEventListener('click', function () {
    var next = heroSection.nextElementSibling;
    if (next) next.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
  });

  var updateScrolled = function () {
    heroSection.classList.toggle('is-scrolled', window.scrollY > 40);
  };
  updateScrolled();
  window.addEventListener('scroll', updateScrolled, { passive: true });
})();

// Only index.html has a #download section with these [data-role] targets — guarded so this
// (shared) script doesn't throw when it runs on a page, like docs.html, that has neither.
var versionEl = document.querySelector('[data-role="version"]');
if (versionEl) {
  fetch('release.json', { cache: 'no-store' })
    .then(function (res) {
      if (!res.ok) throw new Error('release.json not found');
      return res.json();
    })
    .then(function (release) {
      versionEl.textContent = release.name || release.tag;
      document.querySelector('[data-role="meta"]').textContent =
        'Requires Java 25+' + (release.prerelease ? ' · Early alpha build' : '');
    })
    .catch(function () {
      // release.json is generated at deploy time; if it's missing (e.g. local
      // preview without a build step) the download button still works since
      // its href is a static file, only the version label stays generic.
    });
}

// docs.html's baseline "fork" (Establish a production baseline): a plain click-to-switch tab
// pair, not a hover reveal — the two paths are a real either/or (is the service live yet?), and
// a reader picks one deliberately rather than discovering it by accident. Handles every .docs-fork
// group on the page generically, in case another one is ever added.
(function initDocsForks() {
  var groups = document.querySelectorAll('.docs-fork');
  groups.forEach(function (group) {
    var tabs = Array.prototype.slice.call(group.querySelectorAll('.docs-fork-tab'));
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        tabs.forEach(function (t) {
          var selected = t === tab;
          t.classList.toggle('is-active', selected);
          t.setAttribute('aria-selected', selected ? 'true' : 'false');
          t.tabIndex = selected ? 0 : -1;
          var panel = document.getElementById(t.getAttribute('aria-controls'));
          if (panel) panel.hidden = !selected;
        });
      });
    });
  });
})();

// docs.html's sticky section dot-nav: highlights the section currently in view, desktop only
// (see .docs-nav's display:none breakpoint in styles.css — no point wiring scroll-spy for a
// nav the reader can't see). Reuses the same inView() idiom as the reveal wiring above.
(function initDocsNav() {
  var nav = document.querySelector('.docs-nav');
  if (!nav) return;
  var dots = Array.prototype.slice.call(nav.querySelectorAll('.docs-nav-dot'));
  var countEl = nav.querySelector('[data-role="docs-nav-count"]');
  var sections = dots.map(function (dot) {
    return document.getElementById(dot.getAttribute('href').slice(1));
  });

  // Deliberately not inView()'s "50% of the section is visible" — the tour section alone is
  // several viewports tall, so jumping straight to its top via a nav click (or a hash reload)
  // never gets halfway through it and the dot would never light up. Instead: whichever section's
  // top has scrolled up past a fixed reading line is "current" — correct at any scroll position,
  // reached by a gradual scroll or an instant jump alike, and re-derived on every scroll rather
  // than only on a one-time visibility crossing.
  function update() {
    // A small fixed offset, not a fraction of viewport height — some sections (Quickstart,
    // Objectives) are shorter than 30% of a tall viewport, so a generous threshold let the
    // *next* section's top also qualify as "reached" the moment the short one finished landing,
    // jumping the active dot one past where the reader actually is.
    var readingLine = 80;
    var atBottom = window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2;
    var current = atBottom ? sections.length - 1 : 0;
    if (!atBottom) {
      sections.forEach(function (section, i) {
        if (!section) return;
        if (section.getBoundingClientRect().top <= readingLine) current = i;
      });
    }
    dots.forEach(function (dot, j) {
      dot.classList.toggle('is-active', j === current);
      dot.classList.toggle('is-passed', j < current);
    });
    if (countEl) countEl.textContent = String(current);
  }

  update();
  window.addEventListener('scroll', update, { passive: true });
  window.addEventListener('resize', update);

  // Drive the jump ourselves instead of leaving it to the browser's native #hash navigation:
  // a section further down the page can still be loading the lazy images just above or inside
  // it at the moment of the click, and each one reserves more exact space than the browser knew
  // about when it first computed the target position — confirmed by measurement, this alone was
  // enough to land 400+px short of a section named directly by a nav click. A second, immediate
  // scrollIntoView once those images have had time to settle corrects for it; re-running the
  // same call is a no-op once the layout genuinely has stopped moving.
  dots.forEach(function (dot, i) {
    dot.addEventListener('click', function (event) {
      var section = sections[i];
      if (!section) return;
      event.preventDefault();
      history.pushState(null, '', dot.getAttribute('href'));
      var behavior = reduceMotion ? 'auto' : 'smooth';
      section.scrollIntoView({ behavior: behavior, block: 'start' });
      setTimeout(function () {
        section.scrollIntoView({ behavior: behavior, block: 'start' });
      }, 500);
    });
  });
})();
