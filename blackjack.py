"""
=============================================================================
 BLACKJACK — a fully visual casino blackjack game with animations & betting
=============================================================================

SETUP / INSTALLATION
--------------------
1. Install Python 3.8+  (https://python.org)
2. Install pygame:

       pip install pygame

3. Run the game:

       python blackjack.py

Everything (cards, chips, table, particles) is drawn with code —
no image or font assets are required.

HOW TO PLAY
-----------
* The window is freely resizable and HiDPI/4K aware — the game
  scales itself to any size.  Press F11 to toggle fullscreen.
* You start with 1000 chips.
* Use the chip buttons (+10 / -10), or ALL IN, to set your bet,
  then press DEAL.  You cannot deal with a bet of 0 or bet more
  chips than you have.
* HIT to draw a card, STAND to end your turn.  The dealer then
  reveals their hidden card and draws until reaching 17 or more.
* Blackjack (21 with your first two cards) pays 3:2.
  A normal win pays 1:1.  A push returns your bet.
* If you run out of chips the game ends — press NEW GAME to
  restart with 1000 chips.

HOW THE CODE WORKS
------------------
Betting system:
    The bet is deducted from the player's chips the moment DEAL is
    pressed (chips visually slide into the betting circle).  At the
    end of the round the payout is added back: 2x the bet for a win,
    2.5x for a blackjack (3:2 winnings + original bet), 1x for a
    push, and nothing for a loss.

Game logic:
    The game is a small state machine (GameState enum):
        BETTING -> DEALING -> PLAYER_TURN -> DEALER_TURN -> ROUND_OVER
    plus GAME_OVER when the player is broke.  Hand values are
    computed by counting aces as 11 and demoting them to 1 one at a
    time while the hand would bust.  The dealer's hidden card is
    excluded from the visible dealer score until it is flipped.

Animation system:
    Every card on the table is a VisualCard with a current position,
    a target position and a flip state.  Each frame, cards ease
    toward their targets (cubic ease-out) and flips are animated by
    shrinking the card horizontally to zero, swapping face/back, and
    growing it again.  Timed events (dealer pauses, staggered deals)
    are scheduled on a simple [(due_time, callback)] queue processed
    every frame, and win celebrations spawn simple physics particles
    (gravity + velocity) that fade out over their lifetime.
=============================================================================
"""

import math
import random
import sys

import pygame

# ---------------------------------------------------------------------------
# Configuration constants
# ---------------------------------------------------------------------------
WIDTH, HEIGHT = 1024, 720
FPS = 60

CARD_W, CARD_H = 88, 124
CARD_SPACING = 34                      # horizontal offset between fanned cards

DECK_POS = (WIDTH - 130, 60)           # where cards are dealt from
PLAYER_HAND_POS = (WIDTH // 2, 470)    # center of player's hand
DEALER_HAND_POS = (WIDTH // 2, 130)    # center of dealer's hand
BET_CIRCLE_POS = (250, 330)            # betting circle on the table

START_CHIPS = 1000
BET_STEP = 10

DEAL_ANIM_TIME = 0.38                  # seconds for a card to slide out
FLIP_ANIM_TIME = 0.30                  # seconds for a card flip
DEALER_PAUSE = 0.85                    # pause between dealer actions

# Colors
TABLE_GREEN = (21, 88, 52)
TABLE_DARK = (12, 60, 36)
FELT_LINE = (236, 210, 130)
WHITE = (245, 245, 245)
BLACK = (25, 25, 25)
RED = (200, 30, 40)
GOLD = (240, 195, 70)
BTN_BG = (32, 36, 44)
BTN_BG_HOVER = (52, 60, 74)
BTN_BG_DOWN = (20, 22, 28)
BTN_DISABLED = (40, 42, 46)
TXT_DISABLED = (110, 115, 120)
GREEN_MSG = (120, 230, 140)
RED_MSG = (255, 110, 110)
YELLOW_MSG = (255, 225, 120)

RANKS = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"]
SUITS = ["spades", "hearts", "diamonds", "clubs"]
SUIT_COLOR = {"spades": BLACK, "clubs": BLACK, "hearts": RED, "diamonds": RED}


# ---------------------------------------------------------------------------
# Small math helpers
# ---------------------------------------------------------------------------
def ease_out_cubic(t):
    """Cubic ease-out: fast start, gentle stop. t in [0,1]."""
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def lerp(a, b, t):
    return a + (b - a) * t


def card_value(rank):
    """Blackjack value of a rank (ace counted as 11 here, demoted later)."""
    if rank == "A":
        return 11
    if rank in ("J", "Q", "K"):
        return 10
    return int(rank)


def hand_value(cards):
    """Best blackjack value of a hand: aces count 11 unless that busts."""
    total = sum(card_value(c.rank) for c in cards)
    aces = sum(1 for c in cards if c.rank == "A")
    while total > 21 and aces:
        total -= 10            # demote one ace from 11 to 1
        aces -= 1
    return total


# ---------------------------------------------------------------------------
# Card drawing (all art generated in code, surfaces cached)
# ---------------------------------------------------------------------------
_face_cache = {}
_back_cache = None


def draw_suit(surface, suit, cx, cy, size):
    """Draw a suit symbol centered at (cx, cy) with the given size."""
    color = SUIT_COLOR[suit]
    s = size
    if suit == "hearts":
        r = s * 0.28
        pygame.draw.circle(surface, color, (int(cx - r), int(cy - s * 0.18)), int(r))
        pygame.draw.circle(surface, color, (int(cx + r), int(cy - s * 0.18)), int(r))
        pygame.draw.polygon(surface, color, [
            (cx - s * 0.54, cy - s * 0.08),
            (cx + s * 0.54, cy - s * 0.08),
            (cx, cy + s * 0.55)])
    elif suit == "diamonds":
        pygame.draw.polygon(surface, color, [
            (cx, cy - s * 0.55), (cx + s * 0.42, cy),
            (cx, cy + s * 0.55), (cx - s * 0.42, cy)])
    elif suit == "spades":
        r = s * 0.26
        pygame.draw.polygon(surface, color, [
            (cx - s * 0.5, cy + s * 0.12),
            (cx + s * 0.5, cy + s * 0.12),
            (cx, cy - s * 0.55)])
        pygame.draw.circle(surface, color, (int(cx - r), int(cy + s * 0.10)), int(r))
        pygame.draw.circle(surface, color, (int(cx + r), int(cy + s * 0.10)), int(r))
        pygame.draw.polygon(surface, color, [
            (cx - s * 0.16, cy + s * 0.55),
            (cx + s * 0.16, cy + s * 0.55),
            (cx, cy + s * 0.1)])
    elif suit == "clubs":
        r = s * 0.24
        pygame.draw.circle(surface, color, (int(cx), int(cy - s * 0.28)), int(r))
        pygame.draw.circle(surface, color, (int(cx - s * 0.24), int(cy + s * 0.05)), int(r))
        pygame.draw.circle(surface, color, (int(cx + s * 0.24), int(cy + s * 0.05)), int(r))
        pygame.draw.polygon(surface, color, [
            (cx - s * 0.14, cy + s * 0.55),
            (cx + s * 0.14, cy + s * 0.55),
            (cx, cy + s * 0.05)])


def get_card_face(rank, suit, fonts):
    """Return a cached pygame.Surface for the face of a card."""
    key = (rank, suit)
    if key in _face_cache:
        return _face_cache[key]

    surf = pygame.Surface((CARD_W, CARD_H), pygame.SRCALPHA)
    pygame.draw.rect(surf, WHITE, surf.get_rect(), border_radius=10)
    pygame.draw.rect(surf, (170, 170, 175), surf.get_rect(), 2, border_radius=10)

    color = SUIT_COLOR[suit]
    rank_img = fonts["card"].render(rank, True, color)

    # Top-left corner: rank + small suit
    surf.blit(rank_img, (8, 5))
    draw_suit(surf, suit, 8 + rank_img.get_width() // 2, 5 + rank_img.get_height() + 9, 14)

    # Bottom-right corner (rotated 180)
    corner = pygame.Surface((rank_img.get_width() + 6, rank_img.get_height() + 22),
                            pygame.SRCALPHA)
    corner.blit(rank_img, (3, 0))
    draw_suit(corner, suit, 3 + rank_img.get_width() // 2, rank_img.get_height() + 9, 14)
    corner = pygame.transform.rotate(corner, 180)
    surf.blit(corner, (CARD_W - corner.get_width() - 5,
                       CARD_H - corner.get_height() - 3))

    # Big center suit
    draw_suit(surf, suit, CARD_W // 2, CARD_H // 2 + 4, 42)

    _face_cache[key] = surf
    return surf


def get_card_back():
    """Return a cached pygame.Surface for the card back (drawn pattern)."""
    global _back_cache
    if _back_cache is not None:
        return _back_cache

    surf = pygame.Surface((CARD_W, CARD_H), pygame.SRCALPHA)
    pygame.draw.rect(surf, WHITE, surf.get_rect(), border_radius=10)
    inner = pygame.Rect(6, 6, CARD_W - 12, CARD_H - 12)
    pygame.draw.rect(surf, (30, 60, 140), inner, border_radius=7)
    # Diagonal cross-hatch pattern
    for i in range(-CARD_H, CARD_W, 12):
        pygame.draw.line(surf, (70, 105, 190),
                         (inner.left + max(0, i), inner.top + max(0, -i)),
                         (min(inner.right, inner.left + i + CARD_H),
                          min(inner.bottom, inner.top - i + CARD_W)), 2)
    pygame.draw.rect(surf, (210, 175, 90), inner, 3, border_radius=7)
    pygame.draw.circle(surf, (210, 175, 90), (CARD_W // 2, CARD_H // 2), 16, 3)
    _back_cache = surf
    return surf


# ---------------------------------------------------------------------------
# Visual card: position/flip animation wrapper around a (rank, suit)
# ---------------------------------------------------------------------------
class VisualCard:
    def __init__(self, rank, suit, pos, target, face_up, delay=0.0):
        self.rank = rank
        self.suit = suit
        self.x, self.y = pos                # current position (center)
        self.sx, self.sy = pos              # slide start position
        self.tx, self.ty = target           # slide target position
        self.face_up = face_up              # what side is showing *now*
        self.slide_t = 0.0                  # slide progress 0..1
        self.delay = delay                  # wait before sliding (stagger)
        self.flip_t = -1.0                  # -1 = not flipping, else 0..1
        self.settled = False                # finished sliding?

    def set_target(self, target):
        """Re-target the card (used when the hand re-centers)."""
        self.sx, self.sy = self.x, self.y
        self.tx, self.ty = target
        self.slide_t = 0.0
        self.settled = False

    def flip(self):
        if self.flip_t < 0:
            self.flip_t = 0.0

    def update(self, dt):
        # Stagger delay before the card starts moving
        if self.delay > 0:
            self.delay -= dt
            return
        # Slide toward target with easing
        if not self.settled:
            self.slide_t += dt / DEAL_ANIM_TIME
            t = ease_out_cubic(self.slide_t)
            self.x = lerp(self.sx, self.tx, t)
            self.y = lerp(self.sy, self.ty, t)
            if self.slide_t >= 1.0:
                self.x, self.y = self.tx, self.ty
                self.settled = True
        # Flip animation: shrink, swap face at midpoint, grow
        if self.flip_t >= 0:
            self.flip_t += dt / FLIP_ANIM_TIME
            if self.flip_t >= 0.5 and not self.face_up:
                self.face_up = True          # swap at the halfway point
            if self.flip_t >= 1.0:
                self.flip_t = -1.0

    def draw(self, screen, fonts):
        if self.delay > 0:
            return                           # still waiting in the deck
        img = (get_card_face(self.rank, self.suit, fonts)
               if self.face_up else get_card_back())
        # Horizontal squash during a flip (cos curve: 1 -> 0 -> 1)
        if self.flip_t >= 0:
            scale = abs(math.cos(self.flip_t * math.pi))
            w = max(2, int(CARD_W * scale))
            img = pygame.transform.smoothscale(img, (w, CARD_H))
        rect = img.get_rect(center=(int(self.x), int(self.y)))
        # Soft drop shadow
        shadow = pygame.Surface(rect.size, pygame.SRCALPHA)
        pygame.draw.rect(shadow, (0, 0, 0, 70), shadow.get_rect(), border_radius=10)
        screen.blit(shadow, (rect.x + 4, rect.y + 5))
        screen.blit(img, rect)

    @property
    def busy(self):
        """True while the card is sliding or flipping."""
        return self.delay > 0 or not self.settled or self.flip_t >= 0


# ---------------------------------------------------------------------------
# Flying chip: a chip sliding from the chip stack into the betting circle
# ---------------------------------------------------------------------------
class FlyingChip:
    def __init__(self, start, target, color, delay=0.0):
        self.sx, self.sy = start
        self.tx, self.ty = target
        self.x, self.y = start
        self.color = color
        self.t = 0.0
        self.delay = delay
        self.done = False

    def update(self, dt):
        if self.delay > 0:
            self.delay -= dt
            return
        self.t += dt / 0.35
        e = ease_out_cubic(self.t)
        self.x = lerp(self.sx, self.tx, e)
        # Slight arc upward in flight
        self.y = lerp(self.sy, self.ty, e) - math.sin(min(self.t, 1.0) * math.pi) * 28
        if self.t >= 1.0:
            self.done = True

    def draw(self, screen):
        if self.delay > 0:
            return
        draw_chip(screen, int(self.x), int(self.y), 14, self.color)


# ---------------------------------------------------------------------------
# Celebration particle (chips/sparks bouncing on a win)
# ---------------------------------------------------------------------------
class Particle:
    def __init__(self, x, y):
        ang = random.uniform(-math.pi, 0)              # launch upward
        speed = random.uniform(180, 460)
        self.x, self.y = x, y
        self.vx = math.cos(ang) * speed
        self.vy = math.sin(ang) * speed
        self.r = random.randint(4, 9)
        self.color = random.choice([GOLD, (235, 90, 90), (110, 190, 255),
                                    (140, 230, 150), WHITE])
        self.life = random.uniform(0.9, 1.7)
        self.age = 0.0

    def update(self, dt):
        self.age += dt
        self.vy += 900 * dt                            # gravity
        self.x += self.vx * dt
        self.y += self.vy * dt
        if self.y > HEIGHT - 14 and self.vy > 0:       # bounce on the floor
            self.y = HEIGHT - 14
            self.vy *= -0.55
            self.vx *= 0.8

    @property
    def alive(self):
        return self.age < self.life

    def draw(self, screen):
        fade = max(0.0, 1 - self.age / self.life)
        surf = pygame.Surface((self.r * 2, self.r * 2), pygame.SRCALPHA)
        c = (*self.color, int(255 * fade))
        pygame.draw.circle(surf, c, (self.r, self.r), self.r)
        screen.blit(surf, (self.x - self.r, self.y - self.r))


# ---------------------------------------------------------------------------
# Button with hover / pressed visual feedback
# ---------------------------------------------------------------------------
class Button:
    def __init__(self, rect, label, callback, color=GOLD):
        self.rect = pygame.Rect(rect)
        self.label = label
        self.callback = callback
        self.color = color
        self.enabled = True
        self.hover = False
        self.down = False

    def handle_event(self, event):
        if not self.enabled:
            return
        if event.type == pygame.MOUSEMOTION:
            self.hover = self.rect.collidepoint(event.pos)
        elif event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
            if self.rect.collidepoint(event.pos):
                self.down = True
        elif event.type == pygame.MOUSEBUTTONUP and event.button == 1:
            if self.down and self.rect.collidepoint(event.pos):
                self.callback()
            self.down = False

    def draw(self, screen, font):
        if not self.enabled:
            bg, fg, border = BTN_DISABLED, TXT_DISABLED, (70, 72, 76)
        elif self.down:
            bg, fg, border = BTN_BG_DOWN, self.color, self.color
        elif self.hover:
            bg, fg, border = BTN_BG_HOVER, self.color, self.color
        else:
            bg, fg, border = BTN_BG, WHITE, (120, 125, 132)
        # Pressed buttons sink down a couple of pixels
        offset = 2 if (self.down and self.enabled) else 0
        r = self.rect.move(0, offset)
        pygame.draw.rect(screen, bg, r, border_radius=10)
        pygame.draw.rect(screen, border, r, 2, border_radius=10)
        img = font.render(self.label, True, fg)
        screen.blit(img, img.get_rect(center=r.center))


# ---------------------------------------------------------------------------
# Chip drawing helper (concentric circles + edge dashes)
# ---------------------------------------------------------------------------
def draw_chip(screen, x, y, r, color):
    pygame.draw.circle(screen, (0, 0, 0, 60), (x + 2, y + 2), r)   # shadow
    pygame.draw.circle(screen, color, (x, y), r)
    pygame.draw.circle(screen, WHITE, (x, y), int(r * 0.62), 2)
    # Edge dashes
    for i in range(8):
        a = i * math.pi / 4
        x1 = x + math.cos(a) * (r - 3)
        y1 = y + math.sin(a) * (r - 3)
        x2 = x + math.cos(a) * r
        y2 = y + math.sin(a) * r
        pygame.draw.line(screen, WHITE, (x1, y1), (x2, y2), 3)


def chip_color_for(amount):
    """Casino-ish chip color based on denomination."""
    if amount >= 500:
        return (130, 70, 200)     # purple
    if amount >= 100:
        return (35, 35, 40)       # black
    if amount >= 50:
        return (60, 130, 220)     # blue
    if amount >= 25:
        return (50, 160, 90)      # green
    return (210, 60, 60)          # red


# ---------------------------------------------------------------------------
# Game states
# ---------------------------------------------------------------------------
class GameState:
    BETTING = "betting"          # choosing a bet
    DEALING = "dealing"          # initial cards flying out
    PLAYER_TURN = "player"       # hit / stand available
    DEALER_TURN = "dealer"       # dealer reveals + draws
    ROUND_OVER = "round_over"    # result shown, play again
    GAME_OVER = "game_over"      # out of chips


# ---------------------------------------------------------------------------
# Main game class
# ---------------------------------------------------------------------------
class BlackjackGame:
    def __init__(self):
        # On Windows, opt out of OS bitmap scaling so HiDPI (4K) screens
        # don't blur the window; pygame's SCALED mode does the scaling.
        if sys.platform == "win32":
            try:
                import ctypes
                ctypes.windll.shcore.SetProcessDpiAwareness(2)
            except Exception:
                pass
        pygame.init()
        pygame.display.set_caption("Blackjack")
        # SCALED renders the game at its logical 1024x720 resolution and
        # lets the GPU stretch it to any window size / HiDPI display.
        # Mouse coordinates are translated back to logical units for us.
        try:
            self.screen = pygame.display.set_mode(
                (WIDTH, HEIGHT), pygame.SCALED | pygame.RESIZABLE)
        except pygame.error:
            self.screen = pygame.display.set_mode((WIDTH, HEIGHT))
        self.clock = pygame.time.Clock()
        self.fonts = {
            "card": pygame.font.SysFont("georgia", 24, bold=True),
            "ui": pygame.font.SysFont("arial", 22, bold=True),
            "small": pygame.font.SysFont("arial", 17, bold=True),
            "big": pygame.font.SysFont("georgia", 46, bold=True),
            "title": pygame.font.SysFont("georgia", 30, bold=True),
        }
        self.background = self.make_background()

        # Persistent across rounds
        self.chips = START_CHIPS
        self.bet = 0

        # Per-round state
        self.deck = []
        self.player_cards = []        # list[VisualCard]
        self.dealer_cards = []
        self.flying_chips = []
        self.particles = []
        self.pending = []             # [(due_time_seconds, callback)]
        self.now = 0.0                # game clock in seconds
        self.state = GameState.BETTING
        self.message = ""
        self.message_color = WHITE
        self.dealer_hidden = True     # is the dealer's 2nd card face-down?

        self.buttons = self.make_buttons()
        self.update_buttons()

    # -- setup ---------------------------------------------------------------
    def make_background(self):
        """Pre-render the casino table felt with markings."""
        bg = pygame.Surface((WIDTH, HEIGHT))
        # Radial-ish gradient: darker toward the edges
        for y in range(HEIGHT):
            f = abs(y - HEIGHT * 0.45) / (HEIGHT * 0.6)
            c = [int(lerp(g, d, min(1.0, f)))
                 for g, d in zip(TABLE_GREEN, TABLE_DARK)]
            pygame.draw.line(bg, c, (0, y), (WIDTH, y))
        # Subtle felt noise
        for _ in range(2200):
            x, y = random.randrange(WIDTH), random.randrange(HEIGHT)
            shade = random.randint(-7, 7)
            base = bg.get_at((x, y))
            bg.set_at((x, y), tuple(max(0, min(255, v + shade)) for v in base[:3]))
        # Table arc lines
        arc_rect = pygame.Rect(-150, -360, WIDTH + 300, 760)
        pygame.draw.arc(bg, FELT_LINE, arc_rect, math.pi * 1.15, math.pi * 1.85, 3)
        arc_rect2 = arc_rect.inflate(-70, -70)
        pygame.draw.arc(bg, FELT_LINE, arc_rect2, math.pi * 1.18, math.pi * 1.82, 2)
        # Table text
        font = pygame.font.SysFont("georgia", 26, bold=True, italic=True)
        txt = font.render("BLACKJACK  PAYS  3 TO 2", True, FELT_LINE)
        bg.blit(txt, txt.get_rect(center=(WIDTH // 2, 295)))
        font2 = pygame.font.SysFont("georgia", 16, italic=True)
        txt2 = font2.render("Dealer must stand on 17 and draw to 16", True, FELT_LINE)
        bg.blit(txt2, txt2.get_rect(center=(WIDTH // 2, 325)))
        # Betting circle
        pygame.draw.circle(bg, FELT_LINE, BET_CIRCLE_POS, 52, 3)
        txt3 = font2.render("BET", True, FELT_LINE)
        bg.blit(txt3, txt3.get_rect(center=(BET_CIRCLE_POS[0],
                                            BET_CIRCLE_POS[1] + 70)))
        # Deck outline
        deck_rect = pygame.Rect(0, 0, CARD_W + 10, CARD_H + 10)
        deck_rect.center = DECK_POS
        pygame.draw.rect(bg, FELT_LINE, deck_rect, 2, border_radius=12)
        return bg

    def make_buttons(self):
        """Create all UI buttons; visibility is managed in update_buttons."""
        bw, bh = 130, 46
        bottom = HEIGHT - 66
        b = {}
        b["hit"] = Button((WIDTH // 2 - 145, bottom, bw, bh), "HIT",
                          self.on_hit, GREEN_MSG)
        b["stand"] = Button((WIDTH // 2 + 15, bottom, bw, bh), "STAND",
                            self.on_stand, RED_MSG)
        b["deal"] = Button((WIDTH // 2 - 65, bottom, bw, bh), "DEAL",
                           self.on_deal, GOLD)
        b["again"] = Button((WIDTH // 2 - 85, bottom, 170, bh), "PLAY AGAIN",
                            self.on_play_again, GOLD)
        b["newgame"] = Button((WIDTH // 2 - 85, bottom, 170, bh), "NEW GAME",
                              self.on_new_game, GOLD)
        # Bet controls on the left, near the chip stack
        b["bet_up"] = Button((60, bottom, 90, bh), f"+{BET_STEP}",
                             self.on_bet_up, GREEN_MSG)
        b["bet_down"] = Button((160, bottom, 90, bh), f"-{BET_STEP}",
                               self.on_bet_down, RED_MSG)
        b["allin"] = Button((260, bottom, 110, bh), "ALL IN",
                            self.on_all_in, YELLOW_MSG)
        return b

    def update_buttons(self):
        """Enable exactly the buttons that make sense in the current state."""
        s = self.state
        anim = self.cards_busy()
        for btn in self.buttons.values():
            btn.enabled = False
        if s == GameState.BETTING:
            self.buttons["bet_up"].enabled = self.bet + BET_STEP <= self.chips
            self.buttons["bet_down"].enabled = self.bet >= BET_STEP
            self.buttons["allin"].enabled = self.chips > 0
            self.buttons["deal"].enabled = self.bet > 0
        elif s == GameState.PLAYER_TURN and not anim:
            self.buttons["hit"].enabled = True
            self.buttons["stand"].enabled = True
        elif s == GameState.ROUND_OVER and not anim and not self.pending:
            self.buttons["again"].enabled = True
        elif s == GameState.GAME_OVER and not anim and not self.pending:
            self.buttons["newgame"].enabled = True

    # -- helpers -------------------------------------------------------------
    def cards_busy(self):
        return any(c.busy for c in self.player_cards + self.dealer_cards)

    def schedule(self, delay, callback):
        """Run callback after `delay` seconds of game time."""
        self.pending.append((self.now + delay, callback))

    def new_deck(self):
        self.deck = [(r, s) for r in RANKS for s in SUITS]
        random.shuffle(self.deck)

    def hand_targets(self, n, base):
        """Centered, fanned positions for n cards around a base point."""
        first_x = base[0] - (n - 1) * CARD_SPACING / 2
        return [(first_x + i * CARD_SPACING, base[1]) for i in range(n)]

    def add_card(self, hand, base, face_up, delay=0.0):
        """Deal one card from the deck: re-fan the hand and slide it in."""
        rank, suit = self.deck.pop()
        targets = self.hand_targets(len(hand) + 1, base)
        for card, tgt in zip(hand, targets):       # re-center existing cards
            if (card.tx, card.ty) != tgt:
                card.set_target(tgt)
        card = VisualCard(rank, suit, DECK_POS, targets[-1], False, delay)
        hand.append(card)
        if face_up:
            # Flip once the slide finishes
            self.schedule(delay + DEAL_ANIM_TIME * 0.7, card.flip)
        return card

    def visible_dealer_value(self):
        """Dealer score counting only face-up (revealed) cards."""
        shown = [c for c in self.dealer_cards if c.face_up or not self.dealer_hidden]
        return hand_value(shown)

    # -- button callbacks ----------------------------------------------------
    def on_bet_up(self):
        if self.bet + BET_STEP <= self.chips:
            self.bet += BET_STEP

    def on_bet_down(self):
        self.bet = max(0, self.bet - BET_STEP)

    def on_all_in(self):
        self.bet = self.chips

    def on_deal(self):
        """Start a round: take the bet, fly chips in, deal 4 cards."""
        if self.bet <= 0 or self.bet > self.chips:
            return
        self.chips -= self.bet                 # bet leaves the stack now
        self.message = ""
        self.state = GameState.DEALING
        self.player_cards = []
        self.dealer_cards = []
        self.dealer_hidden = True
        self.new_deck()

        # Chips fly from the chip stack into the betting circle
        n_chips = min(8, max(3, self.bet // 25))
        for i in range(n_chips):
            jx = random.randint(-14, 14)
            jy = random.randint(-10, 10)
            self.flying_chips.append(FlyingChip(
                (95, HEIGHT - 150),
                (BET_CIRCLE_POS[0] + jx, BET_CIRCLE_POS[1] + jy),
                chip_color_for(self.bet), delay=i * 0.06))

        # Deal player, dealer, player, dealer(hole) with staggered delays
        d = 0.35
        self.add_card(self.player_cards, PLAYER_HAND_POS, True, d * 0 + 0.3)
        self.add_card(self.dealer_cards, DEALER_HAND_POS, True, d * 1 + 0.3)
        self.add_card(self.player_cards, PLAYER_HAND_POS, True, d * 2 + 0.3)
        self.add_card(self.dealer_cards, DEALER_HAND_POS, False, d * 3 + 0.3)
        self.schedule(d * 3 + 0.3 + DEAL_ANIM_TIME + 0.25, self.check_initial)

    def check_initial(self):
        """After the opening deal: check for blackjacks."""
        p = hand_value(self.player_cards)
        d = hand_value(self.dealer_cards)
        if p == 21 or d == 21:
            self.reveal_dealer()
            self.schedule(FLIP_ANIM_TIME + 0.3, self.resolve_round)
        else:
            self.state = GameState.PLAYER_TURN

    def on_hit(self):
        self.add_card(self.player_cards, PLAYER_HAND_POS, True)
        self.schedule(DEAL_ANIM_TIME + FLIP_ANIM_TIME + 0.1, self.after_hit)
        self.state = GameState.DEALING          # lock buttons while sliding

    def after_hit(self):
        p = hand_value(self.player_cards)
        if p > 21:
            # Bust: reveal dealer's hole card, then settle
            self.reveal_dealer()
            self.schedule(FLIP_ANIM_TIME + 0.4, self.resolve_round)
        elif p == 21:
            self.on_stand()                     # auto-stand on 21
        else:
            self.state = GameState.PLAYER_TURN

    def on_stand(self):
        self.state = GameState.DEALER_TURN
        self.reveal_dealer()
        self.schedule(FLIP_ANIM_TIME + DEALER_PAUSE, self.dealer_step)

    def reveal_dealer(self):
        if self.dealer_hidden:
            self.dealer_hidden = False
            for c in self.dealer_cards:
                if not c.face_up:
                    c.flip()

    def dealer_step(self):
        """One dealer decision; re-scheduled with a pause for drama."""
        if hand_value(self.dealer_cards) < 17:
            self.add_card(self.dealer_cards, DEALER_HAND_POS, True)
            self.schedule(DEAL_ANIM_TIME + FLIP_ANIM_TIME + DEALER_PAUSE,
                          self.dealer_step)
        else:
            self.schedule(0.3, self.resolve_round)

    def resolve_round(self):
        """Compare hands, set the message, and pay out."""
        p = hand_value(self.player_cards)
        d = hand_value(self.dealer_cards)
        player_bj = p == 21 and len(self.player_cards) == 2
        dealer_bj = d == 21 and len(self.dealer_cards) == 2

        if player_bj and not dealer_bj:
            payout = int(self.bet * 2.5)        # 3:2 winnings + bet back
            self.set_result("BLACKJACK!  You win %d" % (payout - self.bet),
                            GREEN_MSG, win=True)
        elif dealer_bj and not player_bj:
            payout = 0
            self.set_result("Dealer has Blackjack — you lose", RED_MSG)
        elif p > 21:
            payout = 0
            self.set_result("BUST!  You lose %d" % self.bet, RED_MSG)
        elif d > 21:
            payout = self.bet * 2
            self.set_result("Dealer busts!  You win %d" % self.bet,
                            GREEN_MSG, win=True)
        elif p > d:
            payout = self.bet * 2
            self.set_result("You win %d!" % self.bet, GREEN_MSG, win=True)
        elif p < d:
            payout = 0
            self.set_result("Dealer wins — you lose %d" % self.bet, RED_MSG)
        else:
            payout = self.bet
            self.set_result("Push — bet returned", YELLOW_MSG)

        self.chips += payout
        self.flying_chips.clear()               # chips leave the circle
        self.bet = min(self.bet, self.chips)    # clamp bet for next round

        if self.chips <= 0:
            self.state = GameState.GAME_OVER
            self.schedule(1.2, lambda: self.set_result(
                "OUT OF CHIPS — GAME OVER", RED_MSG))
        else:
            self.state = GameState.ROUND_OVER

    def set_result(self, text, color, win=False):
        self.message = text
        self.message_color = color
        if win:
            # Celebration: burst of bouncing particles from the hand
            for _ in range(70):
                self.particles.append(Particle(
                    PLAYER_HAND_POS[0] + random.randint(-90, 90),
                    PLAYER_HAND_POS[1] + random.randint(-30, 10)))

    def on_play_again(self):
        self.player_cards = []
        self.dealer_cards = []
        self.message = ""
        self.state = GameState.BETTING
        if self.bet == 0:
            self.bet = min(BET_STEP, self.chips)

    def on_new_game(self):
        self.chips = START_CHIPS
        self.bet = 0
        self.on_play_again()

    # -- update / draw -------------------------------------------------------
    def update(self, dt):
        self.now += dt
        # Run any timed events that are due
        due = [cb for t, cb in self.pending if t <= self.now]
        self.pending = [(t, cb) for t, cb in self.pending if t > self.now]
        for cb in due:
            cb()

        for c in self.player_cards + self.dealer_cards:
            c.update(dt)
        for ch in self.flying_chips:
            ch.update(dt)
        self.particles = [p for p in self.particles if p.alive]
        for p in self.particles:
            p.update(dt)
        self.update_buttons()

    def draw_chip_stack(self):
        """Player's chip pile (bottom-left) — height scales with wealth."""
        n = max(1, min(12, self.chips // 100 + 1)) if self.chips > 0 else 0
        for i in range(n):
            draw_chip(self.screen, 95, HEIGHT - 150 - i * 7, 22,
                      chip_color_for(self.chips))
        label = self.fonts["ui"].render(f"CHIPS: {self.chips}", True, GOLD)
        self.screen.blit(label, (50, HEIGHT - 118))

    def draw_bet_area(self):
        # Static chips resting in the circle while a round is live
        if self.state not in (GameState.BETTING,) and self.bet > 0 and \
                all(ch.done for ch in self.flying_chips):
            for i, ch in enumerate(self.flying_chips):
                draw_chip(self.screen, int(ch.x), int(ch.y), 14, ch.color)
        for ch in self.flying_chips:
            if not ch.done:
                ch.draw(self.screen)
        label = self.fonts["ui"].render(f"BET: {self.bet}", True, WHITE)
        self.screen.blit(label, label.get_rect(
            center=(BET_CIRCLE_POS[0], BET_CIRCLE_POS[1] - 75)))

    def draw_scores(self):
        in_round = self.state not in (GameState.BETTING,) and self.player_cards
        if not in_round:
            return
        # Player score (only count settled/visible cards so it doesn't
        # spoil a card that is still flying)
        shown_p = [c for c in self.player_cards if c.delay <= 0]
        if shown_p:
            p_val = hand_value(shown_p)
            img = self.fonts["ui"].render(f"YOU: {p_val}", True, WHITE)
            self.screen.blit(img, img.get_rect(
                center=(WIDTH // 2, PLAYER_HAND_POS[1] + 95)))
        shown_d = [c for c in self.dealer_cards
                   if c.delay <= 0 and (c.face_up or not self.dealer_hidden)]
        if shown_d:
            d_val = hand_value(shown_d)
            suffix = "" if not self.dealer_hidden else " + ?"
            img = self.fonts["ui"].render(f"DEALER: {d_val}{suffix}", True, WHITE)
            self.screen.blit(img, img.get_rect(
                center=(WIDTH // 2, DEALER_HAND_POS[1] - 95)))

    def draw(self):
        self.screen.blit(self.background, (0, 0))

        # Deck pile (a few stacked card backs)
        for i in range(3):
            back = get_card_back()
            self.screen.blit(back, back.get_rect(
                center=(DECK_POS[0] - i * 2, DECK_POS[1] - i * 2)))

        self.draw_bet_area()
        for c in self.dealer_cards + self.player_cards:
            c.draw(self.screen, self.fonts)
        self.draw_scores()
        self.draw_chip_stack()

        # Result / status message banner
        if self.message:
            img = self.fonts["big"].render(self.message, True, self.message_color)
            rect = img.get_rect(center=(WIDTH // 2, 372))
            pad = rect.inflate(40, 16)
            panel = pygame.Surface(pad.size, pygame.SRCALPHA)
            pygame.draw.rect(panel, (0, 0, 0, 150), panel.get_rect(),
                             border_radius=14)
            self.screen.blit(panel, pad)
            self.screen.blit(img, rect)
        elif self.state == GameState.BETTING:
            hint = self.fonts["title"].render(
                "Place your bet and press DEAL", True, FELT_LINE)
            self.screen.blit(hint, hint.get_rect(center=(WIDTH // 2, 372)))

        for p in self.particles:
            p.draw(self.screen)
        for b in self.buttons.values():
            if b.enabled:
                b.draw(self.screen, self.fonts["ui"])

        pygame.display.flip()

    # -- main loop -----------------------------------------------------------
    def run(self):
        while True:
            dt = self.clock.tick(FPS) / 1000.0
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    pygame.quit()
                    sys.exit()
                elif event.type == pygame.KEYDOWN and event.key == pygame.K_F11:
                    pygame.display.toggle_fullscreen()
                for b in self.buttons.values():
                    b.handle_event(event)
            self.update(dt)
            self.draw()


if __name__ == "__main__":
    BlackjackGame().run()
