# Records docs/images/banking-demo.gif with VHS (https://github.com/charmbracelet/vhs).
#
# From the repository root, once the modules are installed (./mvnw -pl examples/banking-agent -am install -DskipTests):
#
#   vhs examples/banking-agent/demo/banking-demo.tape

Output docs/images/banking-demo.gif

Require curl
Require jq

Set Shell "bash"
Set FontSize 17
Set Width 1240
Set Height 900
Set Padding 22
Set Theme "Catppuccin Mocha"
Set WindowBar Colorful
Set BorderRadius 10
Set TypingSpeed 40ms
Set PlaybackSpeed 1.0

# Start the app and load the helpers off camera.
Hide
Type `cd examples/banking-agent && source demo/demo.sh && PS1='$ ' && demo_start`
Enter
Wait+Screen@120s /ready/
Type "clear"
Enter
Show

Type "# Alice asks the agent to move money. Above $1,000, a second person must approve."
Enter
Sleep 600ms
Type `ask 'Move $5,000 from checking to ACME Corp.'`
Enter
Wait+Screen /asked by alice/
Sleep 2.5s

Type "approve alice"
Enter
Sleep 2s

Type "approve ops@bank.example"
Enter
Wait+Screen /receipt #1 /
Sleep 3s

Type "# Then it tries something destructive."
Enter
Sleep 600ms
Type `ask 'Great. Now clean up by dropping the transactions table.'`
Enter
Wait+Screen /receipt #2 /
Sleep 3s

Type "# Card numbers are masked in what tools return, and in what leaves for the helpdesk."
Enter
Sleep 600ms
Type `ask 'Show me my cards'`
Enter
Wait+Screen /receipt #3 /
Sleep 3s

Type `ask 'Open a support ticket: card 4111 1111 1111 1111 was charged twice'`
Enter
Wait+Screen /receipt #4 /
Sleep 1.5s
Type "helpdesk"
Enter
Wait+Screen /Helpdesk received/
Sleep 3s

Type "# Every call left a signed, hash-chained receipt."
Enter
Sleep 600ms
Type "receipts && verify"
Enter
Wait+Screen /verified/
Sleep 5s

Hide
Type "demo_stop"
Enter
Sleep 1s
