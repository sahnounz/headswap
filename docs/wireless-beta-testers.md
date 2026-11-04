# Headswap: wireless mode (beta) - instructions for testers

Need a machine that has CarLife on head unit has **wireless connection** (Wi-Fi / "WLAN" / "wireless CarLife"). If in the menu CarLife on head unit only USB, do not test.

Beta - separate APK. It is placed on top of the usual Headswap, settings are saved. Cable mode works as before: if you insert a cable, USB. wins

## What to do

1. Install Beta-APK.
2. On the phone, turn on **access point (hotspot)**. If there is a range choice, put 5 GHz. Turn off the auto-disconnect of the access point if it is in the settings.
3. On head unit: connect it to the phone access point (in the settings of Wi-Fi head unit find the phone network and enter a password). Not all head unit has such an item - if not, write about it in the report.
4. Open CarLife on head unit. If CarLife has a choice of connection type, select Wireless (Wi-Fi).
5. In Headswap: Settings → "By wireless connection" → "Waiting for the car by Wi-Fi". On Android 17, allow access to "Devices nearby".
6. Wait until 2–3 minutes. If head unit appeared Android Auto - train 10–15 minutes: touch, music, navigation, voice from the steering button.
7. Whether or not it works, click on "Send Wireless Report" and send the file along with the template below.

**If head unit Connects to the phone itself Bluetooth and Wi-Fi Direct (e.g. Mazda):** hotspot on the phone is not needed. Turn on the phone Bluetooth and Wi-Fi. The phone should be paired with the machine. Click “Wait for the machine on Wi-FiAllow “Devices nearby” (on old ones) Android - geolocation). Then select the wireless connection in CarLife on head unit. In the line "Bluetooth carlife" on the report screen you can see what step it came to: "head unit “I’m in a group,” “I’m in a group.” head unit“He gave head unit IP, waiting TCP from head unit».

Stop mode: the "Stop" button is the same.

## Report Template

```
Machine: Make, Model, Year, Market (China / Other)
head unit: name and version of the software, if visible in the menu
How head unit connected to Wi-Fi: (to the phone access point / head unit itself distributes Wi-Fi / could not find a point)
Connection type in the menu CarLife on head unit (literally, as it says):
Whether the CarLife tab on the head unit was opened before the button in the Headswap: yes / no
Phone: model, version Android / firmware
Range of access point: 2,4/5 GHz/I don't know
Result: AA on the screen head unit - yes / no; if not, what was on the screen Headswap (line "Wireless: ...")
What worked: touch / music / navigation / voice / calls
Breaks: were, after how many minutes
Heating and charging: % at the beginning and end, phone charged or not
Attached report (zip): yes
```

## What's in the report

Application logs, `wireless.log` file (wireless connection steps) and phone information. Password and access point name do not fall into the report. The following are: phone model, name head unit, addresses in the local network of the access point.
