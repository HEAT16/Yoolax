/**
 *
 *	Copyright 2019 SmartThings
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 */
import groovy.json.JsonOutput
import physicalgraph.zigbee.zcl.DataType

metadata {
	definition(name: "Yoolax Zigbee Window Shades", namespace: "heat16", author: "AvianWaves", ocfDeviceType: "oic.d.blind", mnmn: "SmartThings", vid: "generic-shade-3") {
		capability "Actuator"
		capability "Battery"
		capability "Configuration"
		capability "Refresh"
		capability "Window Shade"
		capability "Window Shade Preset"
		capability "Health Check"
		capability "Switch Level"

		command "pause"

		fingerprint manufacturer: "IKEA of Sweden", model: "KADRILJ roller blind", deviceJoinName: "IKEA Window Treatment" // raw description 01 0104 0202 00 09 0000 0001 0003 0004 0005 0020 0102 1000 FC7C 02 0019 1000 //IKEA KADRILJ Blinds
		fingerprint manufacturer: "IKEA of Sweden", model: "FYRTUR block-out roller blind", deviceJoinName: "IKEA Window Treatment" // raw description 01 0104 0202 01 09 0000 0001 0003 0004 0005 0020 0102 1000 FC7C 02 0019 1000 //IKEA FYRTUR Blinds
				fingerprint manufacturer: "ORVIBO", model: "Window Treatment", deviceJoinName: "ORVIBO Window Treatment" 
				fingerprint manufacturer: "yooksmart", model: "Window Treatment", deviceJoinName: "Yooksmart Window Treatment" 
	}

	preferences {
		input "preset", "number", title: "Preset position", description: "Set the window shade preset position", defaultValue: 50, range: "1..100", required: false, displayDuringSetup: false
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"windowShade", type: "generic", width: 6, height: 4) {
			tileAttribute("device.windowShade", key: "PRIMARY_CONTROL") {
				attributeState "open", label: 'Open', action: "close", icon: "http://www.ezex.co.kr/img/st/window_open.png", backgroundColor: "#00A0DC", nextState: "closing"
				attributeState "closed", label: 'Closed', action: "open", icon: "http://www.ezex.co.kr/img/st/window_close.png", backgroundColor: "#ffffff", nextState: "opening"
				attributeState "partially open", label: 'Partially open', action: "close", icon: "http://www.ezex.co.kr/img/st/window_open.png", backgroundColor: "#d45614", nextState: "closing"
				attributeState "opening", label: 'Opening', action: "pause", icon: "http://www.ezex.co.kr/img/st/window_open.png", backgroundColor: "#00A0DC", nextState: "partially open"
				attributeState "closing", label: 'Closing', action: "pause", icon: "http://www.ezex.co.kr/img/st/window_close.png", backgroundColor: "#ffffff", nextState: "partially open"
			}
		}
		standardTile("contPause", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "pause", label:"", icon:'st.sonos.pause-btn', action:'pause', backgroundColor:"#cccccc"
		}
		standardTile("presetPosition", "device.presetPosition", width: 2, height: 2, decoration: "flat") {
			state "default", label: "Preset", action:"presetPosition", icon:"st.Home.home2"
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 1) {
			state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
		}
		valueTile("shadeLevel", "device.level", width: 4, height: 1) {
			state "level", label: 'Shade is ${currentValue}% up', defaultState: true
		}
		valueTile("batteryLevel", "device.battery", width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}
		controlTile("levelSliderControl", "device.level", "slider", width:2, height: 1, inactiveLabel: false) {
			state "level", action:"switch level.setLevel"
		}

		main "windowShade"
		details(["windowShade", "contPause", "presetPosition", "shadeLevel", "levelSliderControl", "refresh", "batteryLevel"])
	}
}

private getCLUSTER_WINDOW_COVERING() { 0x0102 }
private getCOMMAND_OPEN() { 0x00 }
private getCOMMAND_CLOSE() { 0x01 }
private getCOMMAND_PAUSE() { 0x02 }
private getCOMMAND_GOTO_LIFT_PERCENTAGE() { 0x05 }
private getATTRIBUTE_POSITION_LIFT() { 0x0008 }
private getATTRIBUTE_CURRENT_LEVEL() { 0x0000 }
private getCOMMAND_MOVE_LEVEL_ONOFF() { 0x04 }
private getBATTERY_PERCENTAGE_REMAINING() { 0x0021 }

private List<Map> collectAttributes(Map descMap) {
	List<Map> descMaps = new ArrayList<Map>()

	descMaps.add(descMap)

	if (descMap.additionalAttrs) {
		descMaps.addAll(descMap.additionalAttrs)
	}
	return descMaps
}

def installed() {
	log.debug "installed"
	sendEvent(name: "supportedWindowShadeCommands", value: JsonOutput.toJson(["open", "close", "pause"]))
}

// Parse incoming device messages to generate events
def parse(String description) {
	log.debug "description:- ${description}"
	if (description?.startsWith("read attr -")) {
			sendEvent(name: "lastCheckin", value: now)
		Map descMap = zigbee.parseDescriptionAsMap(description)
		if (isBindingTableMessage(description)) {
			parseBindingTableMessage(description)
		} else if (supportsLiftPercentage() && descMap?.clusterInt == CLUSTER_WINDOW_COVERING && descMap.value) {
			log.debug "attr: ${descMap?.attrInt}, value: ${descMap?.value}, descValue: ${Integer.parseInt(descMap.value, 16)}, ${device.getDataValue("model")}"
			List<Map> descMaps = collectAttributes(descMap)
			def liftmap = descMaps.find { it.attrInt == ATTRIBUTE_POSITION_LIFT }
			if (liftmap && liftmap.value) {
				def newLevel = zigbee.convertHexToInt(liftmap.value)
				if (shouldInvertLiftPercentage()) {
					// some devices report % level of being closed (instead of % level of being opened)
					// inverting that logic is needed here to avoid a code duplication
					newLevel = 100 - newLevel
				}
				levelEventHandler(newLevel)
			}
		} else if (!supportsLiftPercentage() && descMap?.clusterInt == zigbee.LEVEL_CONTROL_CLUSTER && descMap.value) {
					log.debug "Lift Percentage (raw): ${descMap?.value}"
			def valueInt = Math.round((zigbee.convertHexToInt(descMap.value)) / 255 * 100)
			levelEventHandler(valueInt)
		} else if (reportsBatteryPercentage() && descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && zigbee.convertHexToInt(descMap?.attrId) == BATTERY_PERCENTAGE_REMAINING && descMap.value) {
			log.debug "Battery (raw hex): 0x${descMap?.value}"
			
			def batteryLevel = zigbee.convertHexToInt(descMap.value)
			log.debug "Battery (raw dec): ${batteryLevel}"
			
			if (isYooksmart()) {
				// By testing, battery level is a scale from ~60 to 200.	Not exactly sure what
				// the lowest level is, so we account for going below zero.	Battery is VERY
				// low in this case anyway.	Convert this to 0-100 scale.
				batteryLevel = batteryLevel - 60
				batteryLevel = batteryLevel * 100
				batteryLevel = batteryLevel.intdiv(140)
			
				log.debug "Battery (Yooksmart converted): ${batteryLevel} percent"
			} else if (isORVIBO()) {
				// By testing, battery level is a scale from ~50 to 200.	Not exactly sure what
				// the lowest level is, so we account for going below zero.	Battery is VERY
				// low in this case anyway.	Convert this to 0-100 scale.
				batteryLevel = batteryLevel - 50
				batteryLevel = batteryLevel * 100
				batteryLevel = batteryLevel.intdiv(150)
			
				log.debug "Battery (ORVIBO converted): ${batteryLevel} percent"
			}
			
			if (batteryLevel < 0) { 
				batteryLevel = 0 
			} else if (batteryLevel > 100) {
				batteryLevel = 100 
			}
			
			batteryPercentageEventHandler(batteryLevel)
		}
	}
}

def levelEventHandler(currentLevel) {
	def lastLevel = device.currentValue("level")
	log.debug "levelEventHandle - currentLevel: ${currentLevel} lastLevel: ${lastLevel}"
	if (lastLevel == "undefined") {
		log.debug "Ignoring invalid level reports."
	} else if (lastLevel == currentLevel) {
		log.debug "Level did not change."
				runIn(getFinalLevelDelay(), "updateFinalState", [overwrite:true])
	} else {
		sendEvent(name: "level", value: currentLevel)
		if (currentLevel == 0 || currentLevel == 100) {
			sendEvent(name: "windowShade", value: currentLevel == 0 ? "closed" : "open")
		} else {
			if (lastLevel < currentLevel) {
				sendEvent([name:"windowShade", value: "opening"])
			} else if (lastLevel > currentLevel) {
				sendEvent([name:"windowShade", value: "closing"])
			}
			runIn(getFinalLevelDelay(), "updateFinalState", [overwrite:true])
		}
	}
}

def updateFinalState() {
	def level = device.currentValue("level")
		
		if (isYooksmart()) {
			// Account for "close to open" or "close to closed" states
				if (level >= 99) {
					log.debug "Yooksmart level normalization: ${level} adjusting to 100 (open)"
					level = 100
		} else if (level <= 1) {
					log.debug "Yooksmart level normalization: ${level} adjusting to 0 (closed)"
					level = 0
				}
		}
		
	log.debug "updateFinalState: ${level}"
	if (level > 0 && level < 100) {
		sendEvent(name: "windowShade", value: "partially open")
	}
}

def batteryPercentageEventHandler(batteryLevel) {
	if (batteryLevel != null) {
			log.debug "Battery provided level: ${batteryLevel}"
		batteryLevel = Math.min(100, Math.max(0, batteryLevel))
				log.debug "Battery min/max level: ${batteryLevel}"
		sendEvent([name: "battery", value: batteryLevel, unit: "%", descriptionText: "{{ device.displayName }} battery was {{ value }}%"])
	}
}

def close() {
	log.info "close()"
	setLevel(0)
}

def open() {
	log.info "open()"
	setLevel(100)
}

def setLevel(data, rate = null) {
	log.info "setLevel()"
	def cmd
	if (supportsLiftPercentage()) {
		if (shouldInvertLiftPercentage() || isYooksmart()) {
			// Some devices keeps % level of being closed (instead of % level of being opened) inverting that logic is needed here.
						// Yooksmart motors are weird.	They report levels using non-inverted values, but you have to SET the level using inverted values.
			data = 100 - data
		}
		cmd = zigbee.command(CLUSTER_WINDOW_COVERING, COMMAND_GOTO_LIFT_PERCENTAGE, zigbee.convertToHexString(data, 2))
	} else {
		cmd = zigbee.command(zigbee.LEVEL_CONTROL_CLUSTER, COMMAND_MOVE_LEVEL_ONOFF, zigbee.convertToHexString(Math.round(data * 255 / 100), 2))
	}
	cmd
}

def pause() {
	log.info "pause()"
	// If the window shade isn't moving when we receive a pause() command then just echo back the current state for the mobile client.
	if (device.currentValue("windowShade") != "opening" && device.currentValue("windowShade") != "closing") {
		sendEvent(name: "windowShade", value: device.currentValue("windowShade"), isStateChange: true, displayed: false)
	}
	zigbee.command(CLUSTER_WINDOW_COVERING, COMMAND_PAUSE)
}

def presetPosition() {
		setLevel(preset ?: 50)
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	log.info "ping()"
	return refresh()
}

def refresh() {
	log.info "refresh()"

	def cmds
	if (supportsLiftPercentage()) {
		cmds = zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTRIBUTE_POSITION_LIFT)
	} else {
		cmds = zigbee.readAttribute(zigbee.LEVEL_CONTROL_CLUSTER, ATTRIBUTE_CURRENT_LEVEL)
	}
		
	 	if (reportsBatteryPercentage()) {
		cmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENTAGE_REMAINING)
	}
		
		log.debug "refresh cmd: ${cmds}"
		
	return cmds
}

def configure() {
	// Device-Watch allows 4 check-in misses from device + ping (plus 2 min lag time)
	log.info "configure()"
	sendEvent(name: "checkInterval", value: 4 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
	log.debug "Configuring Reporting and Bindings."

	def cmds
	if (supportsLiftPercentage()) {
		cmds = zigbee.configureReporting(CLUSTER_WINDOW_COVERING, ATTRIBUTE_POSITION_LIFT, DataType.UINT8, 2, 600, null)
	} else {
		cmds = zigbee.levelConfig()
	}

	if (usesLocalGroupBinding()) {
		cmds += readDeviceBindingTable()
	}

	if (reportsBatteryPercentage()) {
		cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENTAGE_REMAINING, DataType.UINT8, 30, 21600, 0x01)
	}

	return refresh() + cmds
}

def usesLocalGroupBinding() {
	isIkeaKadrilj() || isIkeaFyrtur() || isORVIBO() || isYooksmart()
}

private def parseBindingTableMessage(description) {
	Integer groupAddr = getGroupAddrFromBindingTable(description)
	if (groupAddr) {
		List cmds = addHubToGroup(groupAddr)
		cmds?.collect { new physicalgraph.device.HubAction(it) }
	}
}

private Integer getGroupAddrFromBindingTable(description) {
	log.info "Parsing binding table - '$description'"
	def btr = zigbee.parseBindingTableResponse(description)
	def groupEntry = btr?.table_entries?.find { it.dstAddrMode == 1 }
	log.info "Found ${groupEntry}"
	!groupEntry?.dstAddr ?: Integer.parseInt(groupEntry.dstAddr, 16)
}

private List addHubToGroup(Integer groupAddr) {
	["st cmd 0x0000 0x01 ${CLUSTER_GROUPS} 0x00 {${zigbee.swapEndianHex(zigbee.convertToHexString(groupAddr,4))} 00}", "delay 200"]
}

private List readDeviceBindingTable() {
	["zdo mgmt-bind 0x${device.deviceNetworkId} 0", "delay 200"]
}

def supportsLiftPercentage() {
	isIkeaKadrilj() || isIkeaFyrtur() || isORVIBO() || isYooksmart()
}

def shouldInvertLiftPercentage() {
	return isIkeaKadrilj() || isIkeaFyrtur() || isORVIBO()
}

def reportsBatteryPercentage() {
	return isIkeaKadrilj() || isIkeaFyrtur() || isORVIBO() || isYooksmart()
}

def isIkeaKadrilj() {
	device.getDataValue("model") == "KADRILJ roller blind"
}

def isIkeaFyrtur() {
	device.getDataValue("model") == "FYRTUR block-out roller blind"
}

def isORVIBO() {
	device.getDataValue("manufacturer") == "ORVIBO"
}

def isYooksmart() {
	device.getDataValue("manufacturer") == "yooksmart"
}

def getFinalLevelDelay() {
	if (isORVIBO()) {
		return 2
	} else if (isYooksmart()) {
		return 5
	} else {
		return 1
	}
}
