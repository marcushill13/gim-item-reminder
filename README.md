# GIM Item Reminder

Keep track of the gear your Group Ironman team borrows from Group Storage.

### How it works

1. Create a group and share its six-character code with your teammates.
2. Right-click a weapon or piece of armour in Group Storage and select **Track for GIM**.
3. When someone takes a tracked item out and saves, the group sees who borrowed it.
4. Returning the item updates the original Discord post from red **Borrowed** to green **Returned**.

Each group can set its own Discord webhook in the plugin sidebar. Only the group creator can change it. Item images are included; screenshots are optional and off by default.

### A couple of things to know

- Everyone who wants their borrowing activity recorded needs the plugin running.
- The plugin tracks items your group chooses, not every item in storage.
- Logout reminders are a warning, not a way to prevent disconnections or all logouts.
- Anyone with the group code can join and report activity. Keep the code within your team; records are reminders, not proof of ownership.

### Data sharing

The plugin connects to a shared group service hosted outside RuneLite. When you join a group or use its tracking features, it sends the group code and relevant item information, including your RuneScape name and item quantities, to that service. Your network IP address is visible to the service as part of the connection. If Discord is enabled for your group, item activity and the associated player name are also posted there. Optional screenshots, if you switch them on, are sent to the service and Discord.

For maintainers, see [deployment notes](docs/MAINTAINER.md).
