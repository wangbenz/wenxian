import React from "react";
import { createRoot } from "react-dom/client";
import { CharacterCreationPage } from "../../src/features/onboarding/CharacterCreationPage";
import "./preview.css";

createRoot(document.getElementById("root")!).render(<React.StrictMode><CharacterCreationPage /></React.StrictMode>);
