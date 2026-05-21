import curses
import random
import time

def main(stdscr):
    curses.curs_set(0)
    curses.start_color()
    curses.init_pair(1, curses.COLOR_GREEN, curses.COLOR_BLACK)
    curses.init_pair(2, curses.COLOR_RED, curses.COLOR_BLACK)
    curses.init_pair(3, curses.COLOR_YELLOW, curses.COLOR_BLACK)
    curses.init_pair(4, curses.COLOR_WHITE, curses.COLOR_BLACK)

    sh, sw = stdscr.getmaxyx()
    stdscr.keypad(True)
    stdscr.nodelay(True)

    # Game area borders
    border_y = sh - 3
    border_x = sw - 1

    # Starting snake: center of screen, 3 segments long
    cy, cx = border_y // 2, border_x // 2
    snake = [[cy, cx], [cy, cx - 1], [cy, cx - 2]]
    direction = curses.KEY_RIGHT

    def spawn_food():
        while True:
            y = random.randint(1, border_y - 2)
            x = random.randint(1, border_x - 2)
            if [y, x] not in snake:
                return [y, x]

    food = spawn_food()
    score = 0
    speed = 0.12  # seconds per frame

    def draw_border():
        for x in range(border_x):
            stdscr.addch(0, x, curses.ACS_HLINE, curses.color_pair(4))
            stdscr.addch(border_y, x, curses.ACS_HLINE, curses.color_pair(4))
        for y in range(border_y + 1):
            stdscr.addch(y, 0, curses.ACS_VLINE, curses.color_pair(4))
            try:
                stdscr.addch(y, border_x - 1, curses.ACS_VLINE, curses.color_pair(4))
            except curses.error:
                pass
        stdscr.addch(0, 0, curses.ACS_ULCORNER, curses.color_pair(4))
        stdscr.addch(0, border_x - 1, curses.ACS_URCORNER, curses.color_pair(4))
        stdscr.addch(border_y, 0, curses.ACS_LLCORNER, curses.color_pair(4))
        try:
            stdscr.addch(border_y, border_x - 1, curses.ACS_LRCORNER, curses.color_pair(4))
        except curses.error:
            pass

    def draw_status():
        status = f" Score: {score}   Speed: {int((0.20 - speed) / 0.01 + 1)}  [Q] Quit "
        stdscr.addstr(sh - 2, 2, status, curses.color_pair(3))

    last_move = time.time()

    while True:
        key = stdscr.getch()

        if key == ord('q') or key == ord('Q'):
            break

        # Prevent reversing direction
        opposites = {
            curses.KEY_UP: curses.KEY_DOWN,
            curses.KEY_DOWN: curses.KEY_UP,
            curses.KEY_LEFT: curses.KEY_RIGHT,
            curses.KEY_RIGHT: curses.KEY_LEFT,
        }
        if key in opposites and opposites[key] != direction:
            direction = key

        now = time.time()
        if now - last_move < speed:
            time.sleep(0.01)
            continue
        last_move = now

        # Compute new head
        head = snake[0]
        if direction == curses.KEY_UP:
            new_head = [head[0] - 1, head[1]]
        elif direction == curses.KEY_DOWN:
            new_head = [head[0] + 1, head[1]]
        elif direction == curses.KEY_LEFT:
            new_head = [head[0], head[1] - 1]
        else:
            new_head = [head[0], head[1] + 1]

        # Collision: walls
        if (new_head[0] <= 0 or new_head[0] >= border_y or
                new_head[1] <= 0 or new_head[1] >= border_x - 1):
            break

        # Collision: self
        if new_head in snake:
            break

        snake.insert(0, new_head)

        if new_head == food:
            score += 10
            speed = max(0.04, speed - 0.005)  # speed up slightly
            food = spawn_food()
        else:
            tail = snake.pop()
            stdscr.addch(tail[0], tail[1], ' ')

        # Draw
        stdscr.clear()
        draw_border()
        draw_status()

        stdscr.addch(food[0], food[1], '*', curses.color_pair(2) | curses.A_BOLD)

        for i, seg in enumerate(snake):
            char = '@' if i == 0 else 'o'
            color = curses.color_pair(1) | curses.A_BOLD if i == 0 else curses.color_pair(1)
            try:
                stdscr.addch(seg[0], seg[1], char, color)
            except curses.error:
                pass

        stdscr.refresh()

    # Game over screen
    stdscr.clear()
    msg = f"Game Over! Final score: {score}"
    stdscr.addstr(sh // 2, (sw - len(msg)) // 2, msg, curses.color_pair(2) | curses.A_BOLD)
    hint = "Press any key to exit"
    stdscr.addstr(sh // 2 + 1, (sw - len(hint)) // 2, hint, curses.color_pair(4))
    stdscr.nodelay(False)
    stdscr.refresh()
    stdscr.getch()


if __name__ == "__main__":
    curses.wrapper(main)
