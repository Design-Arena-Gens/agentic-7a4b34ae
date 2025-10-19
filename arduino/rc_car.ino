#include <SoftwareSerial.h>

// Pin assignments using const uint8_t (no magic numbers)
const uint8_t PIN_BT_RX = 10; // HC-05 TX -> RX (to Arduino)
const uint8_t PIN_BT_TX = 11; // HC-05 RX <- TX (from Arduino)

// L293D - Steering (M1)
const uint8_t PIN_IN1 = 2;  // IN1
const uint8_t PIN_IN2 = 3;  // IN2
const uint8_t PIN_EN1 = 5;  // EN1 (PWM)

// L293D - Propulsion (M3)
const uint8_t PIN_IN3 = 4;  // IN3
const uint8_t PIN_IN4 = 6;  // IN4
const uint8_t PIN_EN3 = 7;  // EN3 (PWM)

const uint8_t PIN_LED = 13; // Built-in LED for diagnostics

SoftwareSerial btSerial(PIN_BT_RX, PIN_BT_TX); // RX, TX

// Helper to stop all motors: all IN LOW, EN = 0
void stopAll() {
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  analogWrite(PIN_EN1, 0);

  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
  analogWrite(PIN_EN3, 0);
}

void setPropulsionForward() {
  digitalWrite(PIN_IN3, HIGH);
  digitalWrite(PIN_IN4, LOW);
  analogWrite(PIN_EN3, 255);
}

void setPropulsionBackward() {
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, HIGH);
  analogWrite(PIN_EN3, 255);
}

void setSteeringLeft() {
  digitalWrite(PIN_IN1, HIGH);
  digitalWrite(PIN_IN2, LOW);
  analogWrite(PIN_EN1, 255);
}

void setSteeringRight() {
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, HIGH);
  analogWrite(PIN_EN1, 255);
}

void ackBlink() {
  digitalWrite(PIN_LED, HIGH);
  delay(50);
  digitalWrite(PIN_LED, LOW);
}

void setup() {
  pinMode(PIN_LED, OUTPUT);

  pinMode(PIN_IN1, OUTPUT);
  pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_EN1, OUTPUT);
  pinMode(PIN_IN3, OUTPUT);
  pinMode(PIN_IN4, OUTPUT);
  pinMode(PIN_EN3, OUTPUT);

  stopAll();

  // Self-diagnostic: 3 slow pulses
  for (uint8_t i = 0; i < 3; i++) {
    digitalWrite(PIN_LED, HIGH);
    delay(300);
    digitalWrite(PIN_LED, LOW);
    delay(300);
  }

  btSerial.begin(9600);
}

void loop() {
  if (btSerial.available() > 0) {
    int incoming = btSerial.read();
    char c = (char)incoming;

    switch (c) {
      case 'F':
        setPropulsionForward();
        ackBlink();
        break;
      case 'B':
        setPropulsionBackward();
        ackBlink();
        break;
      case 'L':
        setSteeringLeft();
        ackBlink();
        break;
      case 'R':
        setSteeringRight();
        ackBlink();
        break;
      case 'S':
      default:
        stopAll();
        // For 'S' also acknowledge; for unknown we still treat as stop but no chatter
        if (c == 'S') {
          ackBlink();
        }
        break;
    }
  }
}
